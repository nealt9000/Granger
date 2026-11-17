package app.granger.core.library

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** The Timeline's subject lanes (spec §9.4), as named in a `.gtime` file's meta. */
enum class Lane(val id: String) {
    PHILOSOPHY_RELIGION("philosophy_religion"),
    CIVILISATIONS("civilisations"),
    LANGUAGES("languages"),
    FILM_MUSIC("film_music"),
    ART_LITERATURE("art_literature"),
    SCIENCE("science"),
    WARS("wars"),
    EVENTS_POLITICS("events_politics"),
    PEOPLE("people"),
}

enum class TimeKind { POINT, SPAN, LIFE }

/**
 * One dated article. Years are astronomical (500 BC = -499); [md0]/[md1] are month×100+day or 0; [y1] is null for a
 * point, or for a lifetime or span still running. [prec] is Wikidata's precision (11 day … 9 year, 8 decade, 7 century).
 * [rank] is the item's sitelink count, the notability measure.
 */
data class TimeMark(
    val qid: Long,
    val packId: String,
    val path: String,
    val title: String,
    val kind: TimeKind,
    val y0: Int,
    val md0: Int,
    val y1: Int?,
    val md1: Int?,
    val prec: Int,
    val lanes: Set<Lane>,
    val rank: Int,
) {
    /** The last year the mark covers: its end, the start for a point, [now] for something still running. */
    fun end(now: Int): Int = y1 ?: if (kind == TimeKind.POINT) y0 else now
}

/**
 * A pack's time index (Plan H5b, a read-only SQLite `.gtime` built by tools/timeindex). Lanes are mapped by name from the
 * file's meta, so a file built with other lanes still reads correctly, and lanes it lacks simply match nothing.
 */
class TimeIndex private constructor(
    private val db: SQLiteDatabase,
    val packId: String,
    private val bits: Map<Lane, Int>,
    /** Only marks of these items (see [only]); null for all. */
    private val keep: ((Long) -> Boolean)? = null,
) : Closeable {

    /**
     * This index seen through [keep]: every list (marks, top, around, search…) holds only the items it accepts (a subject's
     * articles, for its quiz: Plan EX Task 3). Counts and the histogram stay the whole pack's. Shares this one's file, so
     * close only one of them.
     */
    fun only(keep: (Long) -> Boolean): TimeIndex = TimeIndex(db, packId, bits, keep)

    /** The marks overlapping [from]..[to] in any of [lanes] (all marks when empty), highest ranked first. */
    fun marks(from: Int, to: Int, lanes: Set<Lane>, limit: Int, now: Int): List<TimeMark> {
        val lane = laneFilter(lanes) ?: return emptyList()
        // Integers go into the SQL itself: Android binds arguments as text, and an expression like COALESCE(...) has no
        // column affinity to turn text back into a number, so every comparison against it would fail.
        return query("y0 <= $to AND COALESCE(y1, CASE kind WHEN 0 THEN y0 ELSE $now END) >= $from$lane ORDER BY rank DESC", limit)
    }

    /** Up to [n] marks starting nearest [year] on each side, by start year. */
    fun around(year: Int, lanes: Set<Lane>, n: Int): List<TimeMark> {
        val lane = laneFilter(lanes) ?: return emptyList()
        val after = query("y0 >= $year$lane ORDER BY y0, rank DESC", n)
        val before = query("y0 < $year$lane ORDER BY y0 DESC, rank DESC", n)
        return before.reversed() + after
    }

    /** The best-known [k] marks starting within [from]..[to] (one slice of the river), highest ranked first. */
    fun top(from: Int, to: Int, lanes: Set<Lane>, k: Int): List<TimeMark> {
        val lane = laneFilter(lanes) ?: return emptyList()
        return query("y0 >= $from AND y0 <= $to$lane ORDER BY rank DESC", k)
    }

    /** The items whose mark falls in [from]..[to] by [ACTIVE], in qid order, for [TimeIndexes.countActive]. */
    internal fun qidsActive(from: Int, to: Int): android.database.Cursor =
        db.rawQuery("SELECT DISTINCT qid FROM marks WHERE $ACTIVE >= $from AND $ACTIVE <= $to ORDER BY qid", null)

    /** The best-known [k] marks falling in [from]..[to] by [ACTIVE], highest ranked first. */
    fun topActive(from: Int, to: Int, k: Int): List<TimeMark> = query("$ACTIVE >= $from AND $ACTIVE <= $to ORDER BY rank DESC", k)

    /** Every mark's (rowid, qid, [ACTIVE] year, rank), for [TimeIndexes.datedIn]. */
    internal fun activeRows(): android.database.Cursor = db.rawQuery("SELECT rowid, qid, $ACTIVE, rank FROM marks", null)

    /** The marks with these rowids (from [activeRows]), in no particular order. */
    internal fun atRows(rows: Collection<Long>): List<TimeMark> =
        if (rows.isEmpty()) emptyList() else query("rowid IN (${rows.joinToString(",")})", rows.size, filtered = false)

    /** The mark for the article at [path], if this pack has it dated. */
    fun find(path: String): TimeMark? {
        val quoted = DatabaseUtils.sqlEscapeString(path)
        return query("path = $quoted", 1).firstOrNull()
    }

    /** Marks whose title contains [text] (any case), best known first: the Timeline's search. */
    fun search(text: String, lanes: Set<Lane>, limit: Int): List<TimeMark> {
        val lane = laneFilter(lanes) ?: return emptyList()
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4)
        if (words.isEmpty()) return emptyList()
        val like = words.joinToString(" AND ") { w ->
            val pattern = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            "title LIKE ${DatabaseUtils.sqlEscapeString(pattern)} ESCAPE '\\'"
        }
        return query("$like$lane ORDER BY rank DESC", limit)
    }

    /** The latest start year here, or null for an empty file. */
    fun latestYear(): Int? = db.rawQuery("SELECT MAX(y0) FROM marks", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }

    /** (start year, lanes as a [Lane.ordinal] bitmask, count) for every year and lane mix: the river's density. */
    fun histogram(): List<Triple<Int, Int, Int>> =
        db.rawQuery("SELECT y0, lanes, COUNT(*) FROM marks GROUP BY y0, lanes", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val fileMask = c.getLong(1)
                    val mask = bits.entries.fold(0) { m, (lane, bit) -> if (fileMask shr bit and 1L == 1L) m or (1 shl lane.ordinal) else m }
                    add(Triple(c.getInt(0), mask, c.getInt(2)))
                }
            }
        }

    /** How many marks each lane holds here; lanes the file doesn't know count 0. */
    fun laneCounts(): Map<Lane, Int> = Lane.entries.associateWith { lane ->
        val bit = bits[lane] ?: return@associateWith 0
        db.rawQuery("SELECT COUNT(*) FROM marks WHERE lanes & ${1L shl bit} != 0", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM marks", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun close() = db.close()

    /** SQL for "in any of [lanes]"; "" for all lanes; null when none of [lanes] exist in this file. */
    private fun laneFilter(lanes: Set<Lane>): String? {
        if (lanes.isEmpty()) return ""
        val mask = lanes.mapNotNull { bits[it] }.fold(0L) { m, b -> m or (1L shl b) }
        if (mask == 0L) return null
        return " AND lanes & $mask != 0"
    }

    /**
     * Up to [limit] marks matching [where] (which may end in ORDER BY). Seen through [keep], the rows are read in order and
     * the first [limit] it accepts are kept, as a LIMIT can't know which those are.
     */
    private fun query(where: String, limit: Int, filtered: Boolean = true): List<TimeMark> {
        val keep = keep.takeIf { filtered }
        val sql = "SELECT qid, path, title, kind, y0, md0, y1, md1, prec, lanes, rank FROM marks WHERE $where" + if (keep == null) " LIMIT $limit" else ""
        return db.rawQuery(sql, null).use { c ->
            buildList {
                while (size < limit && c.moveToNext()) {
                    if (keep != null && !keep(c.getLong(0))) continue
                    val mask = c.getLong(9)
                    add(
                        TimeMark(
                            qid = c.getLong(0), packId = packId, path = c.getString(1), title = c.getString(2),
                            kind = TimeKind.entries.getOrElse(c.getInt(3)) { TimeKind.POINT },
                            y0 = c.getInt(4), md0 = c.getInt(5),
                            y1 = if (c.isNull(6)) null else c.getInt(6), md1 = if (c.isNull(7)) null else c.getInt(7),
                            prec = c.getInt(8),
                            lanes = bits.filterValues { mask shr it and 1L == 1L }.keys,
                            rank = c.getInt(10),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        /**
         * When a mark belongs to an era (Explore): a life at about forty, or its end if sooner (Hitler, born 1889, is a
         * 1900–1945 figure, not an 1800s one); anything else at its start. SQL, over the marks table's columns.
         */
        const val ACTIVE = "(CASE kind WHEN 2 THEN MIN(y0 + 40, COALESCE(y1, y0 + 40)) ELSE y0 END)"
        const val FORMAT = 1

        /** Opens [file] read-only for [packId]; null if it's missing, unreadable or a format this app doesn't read. */
        fun open(file: File, packId: String): TimeIndex? {
            if (!file.isFile) return null
            val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return null
            val meta = runCatching {
                db.rawQuery("SELECT key, value FROM meta", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
            }.getOrNull()
            if (meta == null || meta["format"]?.toIntOrNull() != FORMAT) {
                db.close()
                return null
            }
            val ids = meta["lanes"].orEmpty().split(',')
            val bits = Lane.entries.mapNotNull { lane -> ids.indexOf(lane.id).takeIf { it >= 0 }?.let { lane to it } }.toMap()
            return TimeIndex(db, packId, bits)
        }
    }
}

/** Time index files pushed for development (tools/timeindex/push.py): `index.tsv` lines of `<file>\t<ZIM Name>`. */
object TimeIndexDirectory {
    /** ZIM Name to file, for files that exist; the first line for a name wins. */
    fun listed(dir: File): Map<String, File> {
        val index = File(dir, "index.tsv")
        if (!index.isFile) return emptyMap()
        val out = LinkedHashMap<String, File>()
        for (line in runCatching { index.readLines() }.getOrDefault(emptyList())) {
            val (file, name) = line.split('\t').takeIf { it.size >= 2 } ?: continue
            val f = File(dir, file)
            if (f.isFile && name !in out) out[name] = f
        }
        return out
    }
}

/**
 * Every pack's time index as one: [indexes] in preference order (Wander's: whole encyclopedias in the main language
 * first), so an article in several packs appears once, from the first that has it (Plan H5b, Decision 5).
 */
class TimeIndexes(private val indexes: List<TimeIndex>) {
    val isEmpty: Boolean get() = indexes.isEmpty()

    /** [prefer]: a pack whose copy of a mark wins over the others' (a Spanish topic page gets the Spanish titles). */
    fun marks(from: Int, to: Int, lanes: Set<Lane>, limit: Int, now: Int, prefer: String? = null): List<TimeMark> =
        merge(indexes.sortedBy { if (it.packId == prefer) 0 else 1 }.map { it.marks(from, to, lanes, limit, now) })
            .sortedByDescending { it.rank }.take(limit)

    fun around(year: Int, lanes: Set<Lane>, n: Int): List<TimeMark> {
        val all = merge(indexes.map { it.around(year, lanes, n) })
        val (before, after) = all.partition { it.y0 < year }
        return before.sortedWith(compareBy<TimeMark> { it.y0 }.thenBy { -it.rank }).takeLast(n) +
            after.sortedWith(compareBy<TimeMark> { it.y0 }.thenBy { -it.rank }).take(n)
    }

    /**
     * The river's dots for one view: the best-known [perSlice] marks from each slice between consecutive [edges] (start
     * years, ascending), so a quiet century isn't left blank beside a crowded one, plus the [spans] best-known marks
     * overlapping the whole view (long spans and lifetimes that started before it).
     */
    fun spread(edges: List<Int>, lanes: Set<Lane>, perSlice: Int, spans: Int, now: Int): List<TimeMark> {
        if (edges.size < 2) return emptyList()
        val out = ArrayList<List<TimeMark>>()
        for (i in 0 until edges.size - 1) {
            val a = edges[i]
            val b = if (i == edges.size - 2) edges[i + 1] else edges[i + 1] - 1
            if (b < a) continue
            out += merge(indexes.map { it.top(a, b, lanes, perSlice) }).sortedByDescending { it.rank }.take(perSlice)
        }
        out += marks(edges.first(), edges.last(), lanes, spans, now)
        return merge(out)
    }

    /** The [k] best-known marks starting in [from]..[to], across packs (the games' draw, Plan H5c). */
    fun top(from: Int, to: Int, lanes: Set<Lane>, k: Int): List<TimeMark> =
        merge(indexes.map { it.top(from, to, lanes, k) }).sortedByDescending { it.rank }.take(k)

    /** The mark for an article: from its own pack when that pack is indexed, else from any pack that has the same path. */
    fun find(packId: String, path: String): TimeMark? =
        indexes.firstOrNull { it.packId == packId }?.find(path) ?: indexes.firstNotNullOfOrNull { it.find(path) }

    /** Titles containing every word of [text], across packs, best known first. */
    fun search(text: String, lanes: Set<Lane>, limit: Int): List<TimeMark> =
        merge(indexes.map { it.search(text, lanes, limit) }).sortedByDescending { it.rank }.take(limit)

    /**
     * How many items fall in [from]..[to] by [TimeIndex.ACTIVE] (a life at about forty, anything else at its start), each
     * once however many packs date it: Explore's eras.
     */
    fun countActive(from: Int, to: Int): Int = countDistinct(indexes.map { it.qidsActive(from, to) })

    /** The [k] best-known marks falling in [from]..[to] by [TimeIndex.ACTIVE], across packs. */
    fun topActive(from: Int, to: Int, k: Int): List<TimeMark> =
        merge(indexes.map { it.topActive(from, to, k) }).sortedByDescending { it.rank }.take(k)

    /** These indexes seen through [keep] (see [TimeIndex.only]): a subject's quiz draws only its articles. */
    fun only(keep: (Long) -> Boolean): TimeIndexes = TimeIndexes(indexes.map { it.only(keep) })

    /**
     * The dated items of [set], each once (its first pack's mark, in preference order), with its [TimeIndex.ACTIVE] year
     * and rank: a subject's eras and its oldest-first list (Plan EX Task 3). One pass over each pack's marks; the result is
     * a few primitive arrays, so Top 1M's History (~150k dated) stays a couple of MB.
     */
    fun datedIn(set: SubjectSet): SubjectTime {
        val seen = java.util.BitSet(set.size)
        val pack = IntArrayList()
        val row = LongArrayList()
        val qid = LongArrayList()
        val year = IntArrayList()
        val rank = IntArrayList()
        indexes.forEachIndexed { p, index ->
            index.activeRows().use { c ->
                while (c.moveToNext()) {
                    val q = c.getLong(1)
                    val i = set.indexOf(q)
                    if (i < 0 || seen.get(i)) continue
                    seen.set(i)
                    pack.add(p); row.add(c.getLong(0)); qid.add(q); year.add(c.getInt(2)); rank.add(c.getInt(3))
                }
            }
        }
        val n = qid.size
        return SubjectTime(pack.toArray(), row.toArray(), qid.toArray(), year.toArray(), rank.toArray(), BooleanArray(n) { set.isMain(qid[it]) })
    }

    /** The marks of [items] (indexes into [time]), in that order. */
    fun marksOf(time: SubjectTime, items: List<Int>): List<TimeMark> {
        val found = HashMap<Long, TimeMark>()
        items.groupBy { time.pack[it] }.forEach { (p, its) ->
            indexes.getOrNull(p)?.atRows(its.map { time.row[it] })?.forEach { found[it.qid] = it }
        }
        return items.mapNotNull { found[time.qid[it]] }
    }

    /** The latest year any pack has a mark starting in. */
    fun latestYear(): Int? = indexes.mapNotNull { it.latestYear() }.maxOrNull()

    /** Every pack's year histogram together (overlaps counted per pack: a density guide, not an exact count). */
    fun histogram(): TimeHistogram = TimeHistogram.of(indexes.flatMap { it.histogram() })

    /** Marks per lane across packs, before de-duplication: a guide for which chips to show, not an exact count. */
    fun laneCounts(): Map<Lane, Int> =
        Lane.entries.associateWith { lane -> indexes.sumOf { it.laneCounts()[lane] ?: 0 } }

    private fun merge(lists: List<List<TimeMark>>): List<TimeMark> {
        val seen = HashSet<Long>()
        return lists.flatten().filter { seen.add(it.qid) }
    }
}

/**
 * A subject's dated items ([TimeIndexes.datedIn]): item i is [qid]\[i], placed in [year]\[i] by [TimeIndex.ACTIVE], with its
 * [rank] and whether the subject is its [main] one; [pack] and [row] find its mark again.
 */
class SubjectTime internal constructor(
    internal val pack: IntArray,
    internal val row: LongArray,
    val qid: LongArray,
    val year: IntArray,
    val rank: IntArray,
    val main: BooleanArray,
) {
    val size: Int get() = qid.size

    /** How many fall in [from]..[to]. */
    fun count(from: Int, to: Int): Int = year.count { it in from..to }

    /**
     * The items in [from]..[to]: [oldest] by year (best known first within one), else main subject first, then best known
     * (as the subject page's whole list is).
     */
    fun order(from: Int, to: Int, oldest: Boolean): List<Int> {
        val its = (0 until size).filter { year[it] in from..to }
        return if (oldest) its.sortedWith(compareBy<Int> { year[it] }.thenByDescending { rank[it] }.thenBy { qid[it] })
        else its.sortedWith(compareByDescending<Int> { main[it] }.thenByDescending { rank[it] }.thenBy { qid[it] })
    }
}

/** A growable list of ints without boxing, for one pass over a big index. */
internal class IntArrayList {
    private var a = IntArray(64)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): IntArray = a.copyOf(size)
}

internal class LongArrayList {
    private var a = LongArray(64)
    var size = 0
        private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int): Long = a[i]
    fun toArray(): LongArray = a.copyOf(size)
}

/** How many marks start in each year, by lane mix, held in memory so the river can shade density at any zoom. */
class TimeHistogram private constructor(private val years: IntArray, private val masks: IntArray, private val counts: IntArray) {
    /** Marks starting in [from]..[to] in any of [lanes] (all when empty). */
    fun count(from: Int, to: Int, lanes: Set<Lane>): Int {
        if (to < from || years.isEmpty()) return 0
        val want = lanes.fold(0) { m, l -> m or (1 shl l.ordinal) }
        var i = lowerBound(from)
        var n = 0
        while (i < years.size && years[i] <= to) {
            if (want == 0 || masks[i] and want != 0) n += counts[i]
            i++
        }
        return n
    }

    /** Marks per lane across every year. */
    fun laneCounts(): Map<Lane, Int> = Lane.entries.associateWith { lane ->
        var n = 0
        for (i in years.indices) if (masks[i] and (1 shl lane.ordinal) != 0) n += counts[i]
        n
    }

    private fun lowerBound(y: Int): Int {
        var lo = 0
        var hi = years.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (years[mid] < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = TimeHistogram(IntArray(0), IntArray(0), IntArray(0))

        fun of(rows: List<Triple<Int, Int, Int>>): TimeHistogram {
            val sorted = rows.sortedBy { it.first }
            return TimeHistogram(IntArray(sorted.size) { sorted[it].first }, IntArray(sorted.size) { sorted[it].second }, IntArray(sorted.size) { sorted[it].third })
        }
    }
}

/**
 * The phone's time indexes, opened once and shared by Home, the Timeline and the reader's Show in time; reopened only
 * when the packs or their index files change ([key]).
 */
class TimeIndexCache {
    private var key: List<String>? = null
    private var value: TimeIndexes? = null

    @Synchronized
    fun get(key: List<String>, open: () -> TimeIndexes): TimeIndexes {
        value?.takeIf { this.key == key }?.let { return it }
        return open().also { this.key = key; value = it }
    }
}
