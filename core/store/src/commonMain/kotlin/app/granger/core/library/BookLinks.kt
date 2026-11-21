package app.granger.core.library

/**
 * A Classic's reference links (`.glink`, Plan LL1 Task 7): the book's own work and year, and its mentions. Read on IO.
 * Opened read-only as a [SqlFile] on both phones since Plan I5 (Android: the framework SQLite, `OPEN_READONLY` and
 * `NO_LOCALIZED_COLLATORS`, so nothing is ever written into the file).
 */
class BookLinks private constructor(private val db: SqlFile) : AutoCloseable {
    /** The Classic's catalog id, its Wikidata work (null when none matched) and the year it was written. */
    val bookId: String
    val workQid: Long?
    val year: Int?

    init {
        val row = db.rawQuery("SELECT id, qid, year FROM book", null).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getInt(2)) else null
        }
        bookId = row?.first.orEmpty()
        workQid = row?.second
        year = row?.third
    }

    /** The mentions at [maxTier] and below (1 Light, 2 More), in book order within each chapter. */
    fun mentions(maxTier: Int): List<BookMention> =
        db.rawQuery(
            "SELECT href, start, quote, before, qid, tier FROM mentions WHERE tier <= ? ORDER BY href, start",
            arrayOf(maxTier.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BookMention(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5)))
            }
        }

    /** The first [limit] mentions of [qid], in book order (Task 9's *In your books* passages). */
    fun passagesOf(qid: Long, limit: Int): List<BookMention> =
        db.rawQuery(
            "SELECT href, start, quote, before, qid, tier FROM mentions WHERE qid = ? ORDER BY href, start LIMIT ?",
            arrayOf(qid.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(BookMention(c.getString(0), c.getInt(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5)))
            }
        }

    /** How many of the book's mentions point at [qid] (Task 9's *In your books*). */
    fun countOf(qid: Long): Int =
        db.rawQuery("SELECT COUNT(*) FROM mentions WHERE qid = ?", arrayOf(qid.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun close() = db.close()

    companion object {
        const val KIND = "glink"
        const val SCHEMA_VERSION = 1

        /**
         * Opens [path] read-only; null if it's missing, unreadable, not a links file, a schema this app doesn't read, or
         * (with [sourceSha256]) built from another EPUB than the installed book (spec §5.2: links hide, nothing breaks).
         * One connection: a book's links are read by one caller at a time.
         */
        fun open(path: String, sourceSha256: String? = null): BookLinks? {
            if (!Disk.isFile(path)) return null
            val db = SqlFile.openReadOnly(path, connections = 1) ?: return null
            val meta = SidecarMeta.read(db)
            val ok = meta != null && meta.kind == KIND && meta.schemaVersion in 1..SCHEMA_VERSION &&
                (sourceSha256 == null || meta.sourceSha256 == sourceSha256)
            return if (ok) runCatching { BookLinks(db) }.getOrElse { db.close(); null } else db.close().let { null }
        }
    }
}
