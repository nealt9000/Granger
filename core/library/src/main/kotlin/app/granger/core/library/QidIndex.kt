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
class QidIndex private constructor(private val db: SQLiteDatabase, val packId: String) : Closeable {
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

    override fun close() = db.close()

    companion object {
        const val KIND = "gqid"
        const val SCHEMA_VERSION = 1

        /**
         * Opens [file] read-only for [packId]; null if it's missing, unreadable, not a QID index, a schema this app
         * doesn't read, or (with [sourceSha256]) built from another file than the installed pack (spec §5.2: the
         * feature hides instead of breaking).
         */
        fun open(file: File, packId: String, sourceSha256: String? = null): QidIndex? {
            if (!file.isFile) return null
            val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return null
            val meta = SidecarMeta.read(db)
            val ok = meta != null && meta.kind == KIND && meta.schemaVersion in 1..SCHEMA_VERSION &&
                (sourceSha256 == null || meta.sourceSha256 == sourceSha256)
            if (!ok) {
                db.close()
                return null
            }
            return QidIndex(db, packId)
        }
    }
}

/**
 * Every pack's QID index as one, in preference order (Wander's: whole encyclopedias in the main language first), so an
 * item resolves to the fullest copy on the phone (plan Decision 6).
 */
class QidIndexes(private val indexes: List<QidIndex>) : Closeable {
    val isEmpty: Boolean get() = indexes.isEmpty()

    fun qidOf(packId: String, path: String): Long? = indexes.firstOrNull { it.packId == packId }?.qidOf(path)

    /** Where the item can be read, best pack first; empty when no installed pack has it. */
    fun articlesOf(qid: Long): List<QidArticle> = indexes.flatMap { it.articlesOf(qid) }

    fun otherTitle(packId: String, path: String): String? = indexes.firstOrNull { it.packId == packId }?.otherTitle(path)

    /** The item's values of [prop], from the first pack that has the item. */
    fun linksOf(qid: Long, prop: String): List<String> =
        indexes.firstNotNullOfOrNull { i -> i.linksOf(qid, prop).takeIf { it.isNotEmpty() } }.orEmpty()

    fun itemsWith(prop: String, value: String): List<Long> = indexes.flatMap { it.itemsWith(prop, value) }.distinct()

    fun coordinates(qid: Long): Pair<Double, Double>? = indexes.firstNotNullOfOrNull { it.coordinates(qid) }

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
