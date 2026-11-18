package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** The longest best-known list [QidIndexes.topIn] keeps. */
private const val TOP_KEPT = 400

/** The parts that make a broad topic lead to an item: a kind, a linked article, a person. */
private const val HELD = "${TopicParts.KIND}, ${TopicParts.LINK}, ${TopicParts.PERSON}"

/** One article an item has in a pack: where to open it, and the item's rank (sitelink count). */
data class QidArticle(val qid: Long, val packId: String, val path: String, val rank: Int, val mainSubject: Boolean = true)

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

    /**
     * The articles with [subject]: those whose main subject it is first, then the rest, each most-linked first (France
     * has History too, but History's page opens on World War II). [mainOnly]: only the first kind (a door's examples).
     */
    fun topIn(subject: String, limit: Int, offset: Int = 0, mainOnly: Boolean = false): List<QidArticle> = if (!hasSubjects) emptyList() else
        db.rawQuery(
            "SELECT a.qid, a.path, a.rank, s.ord FROM subjects s JOIN articles a ON a.qid = s.qid WHERE s.subject = ? " +
                (if (mainOnly) "AND s.ord = 0 " else "") + "ORDER BY s.ord > 0, a.rank DESC, a.qid LIMIT ? OFFSET ?",
            arrayOf(subject, limit.toString(), offset.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2), c.getInt(3) == 0)) } }

    /** The items with [subject], in qid order, as a cursor for [countDistinct]; null without subjects. */
    internal fun qidsWith(subject: String): android.database.Cursor? = if (!hasSubjects) null else
        db.rawQuery("SELECT DISTINCT qid FROM subjects WHERE subject = ? ORDER BY qid", arrayOf(subject))

    /** The items with [subject] and whether it's their main one (ord 0), in qid order, for [QidIndexes.subjectSet]. */
    internal fun qidsAndMain(subject: String): android.database.Cursor? = if (!hasSubjects) null else
        db.rawQuery("SELECT qid, MIN(ord) FROM subjects WHERE subject = ? GROUP BY qid ORDER BY qid", arrayOf(subject))

    /** Every item here, in qid order. */
    internal fun allQids(): android.database.Cursor = db.rawQuery("SELECT DISTINCT qid FROM articles ORDER BY qid", null)

    /** Whether the index carries sub-categories (Plan EX editorial pass; files built before it don't). */
    val hasFacets: Boolean by lazy {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'facets'", null).use { it.moveToFirst() }
    }

    /** Whether the index carries broad topics' parts (Plan EX T1; files built before it don't). */
    val hasTopics: Boolean by lazy {
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'topic_parts'", null).use { it.moveToFirst() }
    }

    /** The broad topic [qid]'s page parts (tools/topics/parts.py), or null when it isn't one here. */
    fun topicParts(qid: Long): TopicParts? {
        if (!hasTopics) return null
        val groups = ArrayList<TopicParts.Group>(); val kinds = ArrayList<TopicParts.Kind>()
        val sections = ArrayList<TopicParts.Section>(); val links = ArrayList<TopicParts.Link>()
        val figures = ArrayList<TopicParts.Figure>(); val people = ArrayList<Long>()
        db.rawQuery("SELECT part, item, grp, text, note FROM topic_parts WHERE topic = ? ORDER BY part, ord", arrayOf(qid.toString())).use { c ->
            while (c.moveToNext()) {
                val item = if (c.isNull(1)) 0L else c.getLong(1)
                val grp = if (c.isNull(2)) -1 else c.getInt(2)
                val text = if (c.isNull(3)) "" else c.getString(3)
                val note = if (c.isNull(4)) "" else c.getString(4)
                when (c.getInt(0)) {
                    TopicParts.GROUP -> groups += TopicParts.Group(item, text, grp)
                    TopicParts.KIND -> kinds += TopicParts.Kind(item, grp)
                    TopicParts.SECTION -> sections += TopicParts.Section(note, text)
                    TopicParts.LINK -> links += TopicParts.Link(item, grp)
                    TopicParts.FIGURE -> figures += TopicParts.Figure(note, text, grp)
                    TopicParts.PERSON -> people += item
                }
            }
        }
        if (groups.isEmpty() && kinds.isEmpty() && sections.isEmpty() && people.isEmpty()) return null
        val (under, inlinks) = db.rawQuery("SELECT under, inlinks FROM topics WHERE qid = ?", arrayOf(qid.toString())).use {
            if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else kinds.size to 0
        }
        return TopicParts(qid, packId, under, groups, kinds, sections, links, figures, people, inlinks)
    }

    /**
     * The broad topics whose parts hold [item] (as a kind, a linked article or a person), best known first: the topics
     * that lead to it. There is no index on the column: one pass over the parts (30 ms on Top at the desk).
     */
    fun holders(item: Long, limit: Int): List<QidArticle> = if (!hasTopics) emptyList() else
        db.rawQuery(
            "SELECT a.qid, a.path, a.rank FROM (SELECT DISTINCT topic FROM topic_parts WHERE item = $item AND part IN ($HELD) AND topic != $item) h " +
                "JOIN articles a ON a.qid = h.topic ORDER BY a.rank DESC, a.qid LIMIT $limit",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2))) } }

    /** For each of [items], how many broad topics other than [except] hold it in their parts; an item none holds is left out. */
    fun heldBy(items: Collection<Long>, except: Long?): Map<Long, Int> = if (!hasTopics || items.isEmpty()) emptyMap() else
        db.rawQuery(
            "SELECT item, COUNT(DISTINCT topic) FROM topic_parts WHERE item IN (${items.joinToString(",")}) AND part IN ($HELD) AND topic != item " +
                (if (except != null) "AND topic != $except " else "") + "GROUP BY item",
            null,
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) } }

    /** The broad topics in [facet] (`subject.facet`), best known first. Empty without topics or sub-categories. */
    fun broadInFacet(facet: String, limit: Int): List<QidArticle> {
        val code = codes[facet] ?: return emptyList()
        if (!hasTopics) return emptyList()
        return db.rawQuery(
            "SELECT a.qid, a.path, a.rank FROM topics t JOIN facets f ON f.qid = t.qid AND f.facet = $code JOIN articles a ON a.qid = t.qid " +
                "ORDER BY a.rank DESC, a.qid LIMIT $limit",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2))) } }
    }

    /**
     * The broad topics whose main subject is [subject], best known first: those with at least [minKinds] articles under
     * them and [minFigures] figures (enough for a spread of plates). Empty without topics or subjects.
     */
    fun broadIn(subject: String, minKinds: Int, minFigures: Int, limit: Int): List<QidArticle> = if (!hasTopics || !hasSubjects) emptyList() else
        db.rawQuery(
            "SELECT a.qid, a.path, a.rank FROM topics t JOIN subjects s ON s.qid = t.qid AND s.ord = 0 JOIN articles a ON a.qid = t.qid " +
                // The numbers are written in: a bound argument is text, and a count compared with text is never the greater.
                "WHERE s.subject = ? AND t.under >= $minKinds AND (SELECT COUNT(*) FROM topic_parts p WHERE p.topic = t.qid AND p.part = ${TopicParts.FIGURE}) >= $minFigures " +
                "ORDER BY a.rank DESC, a.qid LIMIT $limit",
            arrayOf(subject),
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2))) } }

    /** Whether the item [qid] is in [facet] (`subject.facet`). */
    fun inFacet(facet: String, qid: Long): Boolean {
        val code = codes[facet] ?: return false
        return db.rawQuery("SELECT 1 FROM facets WHERE facet = ? AND qid = ?", arrayOf(code.toString(), qid.toString())).use { it.moveToFirst() }
    }

    /** The facet list with its names and codes (meta `facets`, JSON {subject: [{id, en, es, code}]}); null without one. */
    internal val facetList: String? by lazy {
        if (!hasFacets) null else runCatching {
            db.rawQuery("SELECT value FROM meta WHERE key = 'facets'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** This index's code for each facet key (`subject.facet`): codes are per build, keys are what the app asks by. */
    private val codes: Map<String, Int> by lazy {
        val json = facetList ?: return@lazy emptyMap()
        runCatching {
            val o = org.json.JSONObject(json)
            buildMap {
                for (subject in o.keys()) {
                    val arr = o.getJSONArray(subject)
                    for (i in 0 until arr.length()) arr.getJSONObject(i).let { f -> if (f.has("code")) put("$subject.${f.getString("id")}", f.getInt("code")) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** The articles in [facet] (`subject.facet`), most-linked first. */
    fun topInFacet(facet: String, limit: Int, offset: Int = 0): List<QidArticle> {
        val code = codes[facet] ?: return emptyList()
        return db.rawQuery(
            "SELECT a.qid, a.path, a.rank FROM facets f JOIN articles a ON a.qid = f.qid WHERE f.facet = ? ORDER BY a.rank DESC, a.qid LIMIT ? OFFSET ?",
            arrayOf(code.toString(), limit.toString(), offset.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(QidArticle(c.getLong(0), packId, c.getString(1), c.getInt(2))) } }
    }

    /** The items in [facet], in qid order, each with 0 (its main subject's), for [countDistinct] and [QidIndexes.facetSet]. */
    internal fun qidsInFacet(facet: String): android.database.Cursor? {
        val code = codes[facet] ?: return null
        return db.rawQuery("SELECT qid, 0 FROM facets WHERE facet = ? ORDER BY qid", arrayOf(code.toString()))
    }

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

    /**
     * The first place the item can be read, [language]'s packs first: [articlesOf]'s first, without asking the packs
     * after the one that has it (a broad topic resolves hundreds of items, and each asked every pack).
     */
    fun firstArticleOf(qid: Long, language: String? = null): QidArticle? {
        val order = if (language == null) indexes else indexes.filter { it.language == language } + indexes.filter { it.language != language }
        return order.firstNotNullOfOrNull { it.articlesOf(qid).firstOrNull() }
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
        subjects.associateWith { s -> counted.computeIfAbsent("subject:$s") { countDistinct(indexes.mapNotNull { it.qidsWith(s) }) } }

    /**
     * Counts already made (the performance pass). These indexes never change once opened (new files make a new
     * [QidIndexes]), and Explore's landing asked for the same counts on every return to it, a second each time. Each is
     * made once: a second asker waits for the first rather than counting again.
     */
    private val counted = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** How many items the packs hold in all, each once. */
    fun itemCount(): Int = counted.computeIfAbsent("items") { countDistinct(indexes.map { it.allQids() }) }

    /**
     * The articles with [subject], main subject first, then most-linked, each item once, in the first pack (in preference
     * order: the library's main language first) that has it. [limit] past [offset].
     */
    fun topIn(subject: String, limit: Int, offset: Int = 0, mainOnly: Boolean = false): List<QidArticle> {
        fun read(): List<QidArticle> {
            val want = offset + limit
            val best = LinkedHashMap<Long, QidArticle>()
            for (index in indexes) for (a in index.topIn(subject, want, mainOnly = mainOnly)) best.putIfAbsent(a.qid, a)
            return best.values.sortedWith(compareByDescending<QidArticle> { it.mainSubject }.thenByDescending { it.rank }.thenBy { it.qid })
                .drop(offset).take(limit)
        }
        // A subject's best known from the top are asked for again and again (the landing's doors, on every return to it).
        return if (offset == 0 && limit <= TOP_KEPT) tops.computeIfAbsent("$subject|$limit|$mainOnly") { read() } else read()
    }

    private val tops = java.util.concurrent.ConcurrentHashMap<String, List<QidArticle>>()

    /**
     * Every item with [subject] across the packs, each once, as a sorted set (Plan EX Task 3: a subject's eras, its quiz).
     * An item's subjects are the same in every pack, so its main flag is too.
     */
    fun subjectSet(subject: String): SubjectSet = merged(indexes.mapNotNull { it.qidsAndMain(subject) })

    /** Whether any index carries sub-categories (Plan EX editorial pass). */
    val hasFacets: Boolean get() = indexes.any { it.hasFacets }

    /**
     * The broad topic [qid]'s page parts (Plan EX T1), from [packId]'s own index first, then the packs in [language]
     * (parts are in their pack's language: section titles, captions, group names); null when it isn't a broad topic.
     */
    fun topicParts(qid: Long, packId: String? = null, language: String? = null): TopicParts? {
        val order = indexes.filter { it.packId == packId } + indexes.filter { it.packId != packId && (language == null || it.language == language) }
        return order.firstNotNullOfOrNull { it.topicParts(qid) }
    }

    /**
     * [subject]'s broad topics fit for a spread ([QidIndex.broadIn]), best known first, each item once, in the first pack
     * that has it. With [language], only its packs' topics when they have any (a spread reads in the reader's language:
     * its sentence and captions come from the pack); the other packs' otherwise.
     */
    fun broadIn(subject: String, minKinds: Int, minFigures: Int, limit: Int, language: String? = null): List<QidArticle> {
        fun from(some: List<QidIndex>): List<QidArticle> {
            val best = LinkedHashMap<Long, QidArticle>()
            for (index in some) for (a in index.broadIn(subject, minKinds, minFigures, limit)) best.putIfAbsent(a.qid, a)
            return best.values.sortedWith(compareByDescending<QidArticle> { it.rank }.thenBy { it.qid }).take(limit)
        }
        if (language != null) from(indexes.filter { it.language == language }).takeIf { it.isNotEmpty() }?.let { return it }
        return from(indexes)
    }

    /**
     * The broad topics in [facet], best known first, each item once; with [language], only its packs' when they have any
     * (their parts are in the pack's language).
     */
    fun broadInFacet(facet: String, limit: Int, language: String? = null): List<QidArticle> {
        fun from(some: List<QidIndex>): List<QidArticle> {
            val best = LinkedHashMap<Long, QidArticle>()
            for (index in some) for (a in index.broadInFacet(facet, limit)) best.putIfAbsent(a.qid, a)
            return best.values.sortedWith(compareByDescending<QidArticle> { it.rank }.thenBy { it.qid }).take(limit)
        }
        if (language != null) from(indexes.filter { it.language == language }).takeIf { it.isNotEmpty() }?.let { return it }
        return from(indexes)
    }

    /** The broad topics that lead to [item] ([QidIndex.holders]), best known first, each once; [language]'s packs' when they have any. */
    fun holders(item: Long, limit: Int, language: String? = null): List<QidArticle> {
        fun from(some: List<QidIndex>): List<QidArticle> {
            val best = LinkedHashMap<Long, QidArticle>()
            for (index in some) for (a in index.holders(item, limit)) best.putIfAbsent(a.qid, a)
            return best.values.sortedWith(compareByDescending<QidArticle> { it.rank }.thenBy { it.qid }).take(limit)
        }
        if (language != null) from(indexes.filter { it.language == language }).takeIf { it.isNotEmpty() }?.let { return it }
        return from(indexes)
    }

    /** For each of [items], how many broad topics other than [except] lead to it: the most any one pack counts (packs share their topics). */
    fun heldBy(items: Collection<Long>, except: Long?): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        for (index in indexes) for ((item, n) in index.heldBy(items, except)) out.merge(item, n, ::maxOf)
        return out
    }

    /** Every room that holds the item [qid], in the subjects' own order and each subject's curated order. */
    fun roomsOf(qid: Long): List<Facet> {
        val json = indexes.firstNotNullOfOrNull { it.facetList } ?: return emptyList()
        val subjects = runCatching { org.json.JSONObject(json).keys().asSequence().toList() }.getOrDefault(emptyList())
        return subjects.flatMap { Facet.parse(json, it) }.filter { f -> indexes.any { it.inFacet(f.key, qid) } }
    }

    /** The first of [subject]'s sub-categories, in their curated order, that holds the item [qid]; null when none does. */
    fun facetOf(qid: Long, subject: String): Facet? = facets(subject).firstOrNull { f -> indexes.any { it.inFacet(f.key, qid) } }

    /** [subject]'s sub-categories, in their curated order, with their names; empty without any. */
    fun facets(subject: String): List<Facet> =
        parsed.computeIfAbsent(subject) { indexes.firstNotNullOfOrNull { it.facetList }?.let { Facet.parse(it, subject) }.orEmpty() }

    private val parsed = java.util.concurrent.ConcurrentHashMap<String, List<Facet>>()

    /** How many items each of [subject]'s sub-categories holds across the packs, each item once. */
    fun facetCounts(subject: String): Map<String, Int> =
        facets(subject).associate { f -> f.id to counted.computeIfAbsent("room:${f.key}") { countDistinct(indexes.mapNotNull { it.qidsInFacet(f.key) }) } }

    /** The articles in [facet] (`subject.facet`), most-linked first, each item once, in the first pack that has it. */
    fun topInFacet(facet: String, limit: Int, offset: Int = 0): List<QidArticle> {
        val best = LinkedHashMap<Long, QidArticle>()
        for (index in indexes) for (a in index.topInFacet(facet, offset + limit)) best.putIfAbsent(a.qid, a)
        return best.values.sortedWith(compareByDescending<QidArticle> { it.rank }.thenBy { it.qid }).drop(offset).take(limit)
    }

    /** Every item in [facet] across the packs, each once, as a sorted set (all main: a lens lists its own articles, from any subject). */
    fun facetSet(facet: String): SubjectSet = merged(indexes.mapNotNull { it.qidsInFacet(facet) })

    /** The qid-sorted (qid, ord) [cursors] merged into one set, each item once, main when any pack has ord 0; closes them. */
    private fun merged(all: List<android.database.Cursor>): SubjectSet {
        val cursors = all.filter { c -> c.moveToFirst().also { if (!it) c.close() } }.toMutableList()
        val qids = LongArrayList()
        val main = java.util.BitSet()
        while (cursors.isNotEmpty()) {
            val low = cursors.minOf { it.getLong(0) }
            var isMain = false
            val each = cursors.iterator()
            while (each.hasNext()) {
                val c = each.next()
                if (c.getLong(0) != low) continue
                if (c.getInt(1) == 0) isMain = true
                if (!c.moveToNext()) { c.close(); each.remove() }
            }
            if (isMain) main.set(qids.size)
            qids.add(low)
        }
        return SubjectSet(qids.toArray(), main)
    }

    override fun close() = indexes.forEach { it.close() }
}

/**
 * One of a subject's sub-categories (Plan EX editorial pass; tools/subjects/facets.json): [id] within the subject, and
 * its names. [key] is how the index files it (`subject.facet`).
 */
data class Facet(val subject: String, val id: String, val en: String, val es: String) {
    val key: String get() = "$subject.$id"

    /** Its name in [language] ("es" for Spanish), English otherwise. */
    fun name(language: String): String = if (language == "es" && es.isNotBlank()) es else en

    companion object {
        /** [subject]'s facets from the index's JSON list, in order; empty when it has none or the list won't read. */
        fun parse(json: String, subject: String): List<Facet> = runCatching {
            val arr = org.json.JSONObject(json).optJSONArray(subject) ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Facet(subject, o.getString("id"), o.optString("en"), o.optString("es"))
            }
        }.getOrDefault(emptyList())
    }
}

/** A subject's items ([QidIndexes.subjectSet]), sorted, with which have it as their main subject. */
class SubjectSet internal constructor(private val qids: LongArray, private val main: java.util.BitSet) {
    val size: Int get() = qids.size

    /** The item's place in the set, or a negative number when it isn't in it. */
    fun indexOf(qid: Long): Int = java.util.Arrays.binarySearch(qids, qid)

    operator fun contains(qid: Long): Boolean = indexOf(qid) >= 0

    fun isMain(qid: Long): Boolean = indexOf(qid).let { it >= 0 && main.get(it) }

    /** How many items this set and [other] both hold (one pass over the two sorted lists). */
    fun shared(other: SubjectSet): Int {
        var i = 0
        var j = 0
        var n = 0
        while (i < qids.size && j < other.qids.size) {
            val a = qids[i]
            val b = other.qids[j]
            when {
                a == b -> { n++; i++; j++ }
                a < b -> i++
                else -> j++
            }
        }
        return n
    }

    companion object {
        val EMPTY = SubjectSet(LongArray(0), java.util.BitSet())
    }
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

/**
 * A broad topic's page parts (Plan EX T1, tools/topics/parts.py), read from one pack's index: its kinds (the articles
 * under its class, best known first) and their groups, its article's sections with the articles each links, its
 * figures with their captions, and the people characteristic of it. Items are QIDs, to resolve in any installed pack.
 */
data class TopicParts(
    val qid: Long,
    /** The pack whose index these came from (its language; its ZIM holds the figures, when it has pictures). */
    val packId: String,
    /** How many of its pack's articles are under it (the kinds kept are the best known of these). */
    val under: Int,
    val groups: List<Group>,
    val kinds: List<Kind>,
    val sections: List<Section>,
    val links: List<Link>,
    val figures: List<Figure>,
    val people: List<Long>,
    /** How many of its pack's articles link to it. */
    val inlinks: Int = 0,
) {
    /** A group of kinds: its class, its name in the pack's language, how many of the kinds it holds. */
    data class Group(val cls: Long, val name: String, val count: Int)
    /** A kind: its item and its group (an index into [groups]), or -1. */
    data class Kind(val qid: Long, val group: Int)
    /** One of the article's sections: its anchor and title. */
    data class Section(val anchor: String, val title: String)
    /** An article the topic's article links, in [section] (an index into [sections]; -1 the lead). */
    data class Link(val qid: Long, val section: Int)
    /** A figure: its picture's path in the pack's ZIM, its caption, its section (-1 the lead). */
    data class Figure(val image: String, val caption: String, val section: Int)

    companion object {
        const val GROUP = 1
        const val KIND = 2
        const val SECTION = 3
        const val LINK = 4
        const val FIGURE = 5
        const val PERSON = 6
    }
}
