package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** One article an item has in a pack: where to open it, and the item's rank (sitelink count). */
data class QidArticle(val qid: Long, val packId: String, val path: String, val rank: Int)

/**
 * A Wikipedia pack's QID index (`.gqid`, Plan LL0 Task 5): every real article's Wikidata item, its rank, its title in
 * the other language, and the item's join properties (author P50, Gutenberg P2034, MusicBrainz P434…). The Linked
 * Library joins on QIDs only (spec §5.3), so a link from a book, a recording or another pack resolves here.
 */
class QidIndex private constructor(private val db: SQLiteDatabase, val packId: String, val language: String? = null) : Closeable {
    fun qidOf(path: String): Long? =
        db.rawQuery("SELECT qid FROM articles WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst()) it.getLong(0) else null }

    /** The article's title in the other language (Spanish on an English pack, English on a Spanish one); null if none. */
    fun otherTitle(path: String): String? =
        db.rawQuery("SELECT other FROM articles WHERE path = ?", arrayOf(path)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    fun articlesOf(qid: Long): List<QidArticle> =
        db.rawQuery("SELECT path, rank FROM articles WHERE qid = ?", arrayOf(qid.toString())).use { c ->
            buildList { while (c.moveToNext()) add(QidArticle(qid, packId, c.getString(0), c.getInt(1))) }
        }

    /** The item's values of [prop]: "Q123" for an item, the string itself for an identifier. */
    fun linksOf(qid: Long, prop: String): List<String> =
        db.rawQuery("SELECT value FROM links WHERE qid = ? AND prop = ?", arrayOf(qid.toString(), prop)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** The items whose [prop] is [value]: Gutenberg ebook "1342" -> Pride and Prejudice; author "Q36322" -> her works. */
    fun itemsWith(prop: String, value: String): List<Long> =
        db.rawQuery("SELECT DISTINCT qid FROM links WHERE prop = ? AND value = ?", arrayOf(prop, value)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun coordinates(qid: Long): Pair<Double, Double>? =
        db.rawQuery("SELECT lat, lon FROM coords WHERE qid = ?", arrayOf(qid.toString())).use { if (it.moveToFirst()) it.getDouble(0) to it.getDouble(1) else null }

    /** Whether the index carries subjects (Plan EX Task 1; files built before it don't). */
    val hasSubjects: Boolean by lazy {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'subjects'", null).use { it.moveToFirst() }
    }

    /** The item's subjects, main first (subjects.json ids); empty without any. */
    fun subjectsOf(qid: Long): List<String> = if (!hasSubjects) emptyList() else
        db.rawQuery("SELECT subject FROM subjects WHERE qid = ? ORDER BY ord", arrayOf(qid.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** The articles with [subject] (main or not), most-linked first: the item's article here and its rank. */
    fun topIn(subject: String, limit: Int, offset: Int = 0): List<QidArticle> = if (!hasSubjects) emptyList() else
        db.rawQuery(
            "SELECT a.qid, a.path, a.rank FROM subjects s JOIN articles a ON a.qid = s.qid WHERE s.subject = ? " +
                "ORDER BY a.rank DESC, a.qid LIMIT ? OFFSET ?",
            arrayOf(subject, limit.toString(), offset.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2))) } }

    /** The items with [subject], in qid order, as a cursor for [countDistinct]; null without subjects. */
    internal fun qidsWith(subject: String): android.database.Cursor? = if (!hasSubjects) null else
        db.rawQuery("SELECT DISTINCT qid FROM subjects WHERE subject = ? ORDER BY qid", arrayOf(subject))

    /** Every item here, in qid order. */
    internal fun allQids(): android.database.Cursor = db.rawQuery("SELECT DISTINCT qid FROM articles ORDER BY qid", null)

    override fun close() = db.close()

    companion object {
        const val KIND = "gqid"
        const val SCHEMA_VERSION = 1

        /**
         * Opens [file] read-only for [packId]; null if it's missing, unreadable, not a QID index, a schema this app
         * doesn't read, or (with [sourceSha256]) built from another file than the installed pack (spec §5.2: the
         * feature hides instead of breaking). One index serves every flavour of an edition (Plan LL0: the catalog gives
         * the nopic build to maxi and mini too), so with [sourceName], the installed pack's file name, an index built
         * from the same edition in another flavour is accepted as well.
         */
        fun open(file: File, packId: String, sourceSha256: String? = null, sourceName: String? = null): QidIndex? {
            if (!file.isFile) return null
            val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return null
            val meta = SidecarMeta.read(db)
            val ok = meta != null && meta.kind == KIND && meta.schemaVersion in 1..SCHEMA_VERSION &&
                (sourceSha256 == null || meta.sourceSha256 == sourceSha256 || sameEdition(meta.values["sourceZim"], sourceName))
            if (!ok) {
                db.close()
                return null
            }
            return QidIndex(db, packId, meta.values["sourceZim"]?.let { LANGUAGE.find(it)?.groupValues?.get(1) })
        }

        /** Whether two Kiwix file names are one edition: the same name and date, whatever the flavour. */
        fun sameEdition(a: String?, b: String?): Boolean {
            val x = a?.let(::edition) ?: return false
            return x == b?.let(::edition)
        }

        /** `wikipedia_en_top_maxi_2026-09-14.zim` -> `wikipedia_en_top_2026-09-14`. */
        private fun edition(name: String): String? {
            val m = EDITION.matchEntire(name.substringAfterLast('/')) ?: return null
            return m.groupValues[1] + "_" + m.groupValues[2]
        }

        /** `wikipedia_es_top_nopic_2026-10-10.zim` -> `es`. */
        private val LANGUAGE = Regex("^[a-z]+_([a-z]{2,3})_")

        private val EDITION = Regex("(.+?)(?:_(?:maxi|mini|nopic))?_(\\d{4}-\\d{2}(?:-\\d{2})?)\\.zim")
    }
}

/**
 * Every pack's QID index as one, in preference order (Wander's: whole encyclopedias in the main language first), so an
 * item resolves to the fullest copy on the phone (plan Decision 6).
 */
class QidIndexes(private val indexes: List<QidIndex>) : Closeable {
    val isEmpty: Boolean get() = indexes.isEmpty()

    fun qidOf(packId: String, path: String): Long? = indexes.firstOrNull { it.packId == packId }?.qidOf(path)

    /** Whether [packId] has an index of its own here. */
    fun has(packId: String): Boolean = indexes.any { it.packId == packId }

    /**
     * The item of the page titled [path] in any index of [language]: for a Wikipedia pack with no index of its own (an
     * older subject pack), whose pages share their titles with the language's other Wikipedias.
     */
    fun qidOfTitle(path: String, language: String): Long? =
        indexes.asSequence().filter { it.language == language }.firstNotNullOfOrNull { it.qidOf(path) }

    /** Where the item can be read, best pack first; empty when no installed pack has it. */
    fun articlesOf(qid: Long): List<QidArticle> = indexes.flatMap { it.articlesOf(qid) }

    /** [articlesOf], the packs in [language] first (a book's own language), each side kept in preference order. */
    fun articlesOf(qid: Long, language: String?): List<QidArticle> {
        if (language == null) return articlesOf(qid)
        val (mine, others) = indexes.partition { it.language == language }
        return (mine + others).flatMap { it.articlesOf(qid) }
    }

    fun otherTitle(packId: String, path: String): String? = indexes.firstOrNull { it.packId == packId }?.otherTitle(path)

    /** The item's values of [prop], from the first pack that has the item. */
    fun linksOf(qid: Long, prop: String): List<String> =
        indexes.firstNotNullOfOrNull { i -> i.linksOf(qid, prop).takeIf { it.isNotEmpty() } }.orEmpty()

    fun itemsWith(prop: String, value: String): List<Long> = indexes.flatMap { it.itemsWith(prop, value) }.distinct()

    fun coordinates(qid: Long): Pair<Double, Double>? = indexes.firstNotNullOfOrNull { it.coordinates(qid) }

    /** Whether any index carries subjects (Plan EX). */
    val hasSubjects: Boolean get() = indexes.any { it.hasSubjects }

    /** The item's subjects, main first, from the first pack that has them. */
    fun subjectsOf(qid: Long): List<String> = indexes.firstNotNullOfOrNull { i -> i.subjectsOf(qid).takeIf { it.isNotEmpty() } }.orEmpty()

    /**
     * How many items have each of [subjects] across every pack, each item once (English and Spanish Top share ~22k): the
     * packs' qid lists are merged in order, never held in memory. Explore's subject doors.
     */
    fun subjectCounts(subjects: Collection<String>): Map<String, Int> =
        subjects.associateWith { s -> countDistinct(indexes.mapNotNull { it.qidsWith(s) }) }

    /** How many items the packs hold in all, each once. */
    fun itemCount(): Int = countDistinct(indexes.map { it.allQids() })

    /**
     * The most-linked articles with [subject], each item once, in the first pack (in preference order: the library's
     * main language first) that has it. [limit] past [offset].
     */
    fun topIn(subject: String, limit: Int, offset: Int = 0): List<QidArticle> {
        val want = offset + limit
        val best = LinkedHashMap<Long, QidArticle>()
        for (index in indexes) for (a in index.topIn(subject, want)) best.putIfAbsent(a.qid, a)
        return best.values.sortedWith(compareByDescending<QidArticle> { it.rank }.thenBy { it.qid }).drop(offset).take(limit)
    }

    override fun close() = indexes.forEach { it.close() }
}

/** The meta table every sidecar keeps (Linked Library spec §5.1; tools/sidecar/meta.py writes it). */
data class SidecarMeta(val kind: String, val schemaVersion: Int, val sourceSha256: String, val values: Map<String, String>) {
    companion object {
        fun read(db: SQLiteDatabase): SidecarMeta? = runCatching {
            val m = db.rawQuery("SELECT key, value FROM meta", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
            SidecarMeta(m["kind"].orEmpty(), m["schemaVersion"]?.toIntOrNull() ?: 0, m["sourceSha256"].orEmpty(), m)
        }.getOrNull()
    }
}

/**
 * The number of distinct longs over [cursors], each sorted ascending on column 0 (one pack's qids each), merged in step so
 * no list is held in memory; closes them. Explore counts an item once however many packs hold it.
 */
internal fun countDistinct(cursors: List<android.database.Cursor>): Int {
    val open = cursors.filter { it.moveToFirst() }.toMutableList()
    cursors.filterNot { it in open }.forEach { it.close() }
    var n = 0
    while (open.isNotEmpty()) {
        val low = open.minOf { it.getLong(0) }
        n++
        val each = open.iterator()
        while (each.hasNext()) {
            val c = each.next()
            if (c.getLong(0) == low && !c.moveToNext()) { c.close(); each.remove() }
        }
    }
    return n
}
