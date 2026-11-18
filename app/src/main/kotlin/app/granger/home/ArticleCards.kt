package app.granger.home

import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import app.granger.core.zim.ZimArchive
import java.io.File

/**
 * An article as a card shows it (its title, opening sentences and picture), read once and remembered. Making a card
 * means reading the article out of its pack and parsing its HTML twice, about a tenth of a second each on a phone, and
 * a screen of plates asks for dozens; the picture was already kept as a file, but finding which file still took the
 * parse. So the card itself is kept: in memory while the app runs, and in a small database under the app's cache (the
 * OS may clear it; cards are then made again). Blocking; call off the main thread. Safe to call from several threads.
 */
class ArticleCards(private val file: File, private val thumbs: ArticleThumbnails) {
    data class Card(val title: String, val summary: String, val thumbnail: String?)

    private val recent = LruCache<String, Card>(RECENT)
    private val db: SQLiteDatabase? by lazy { open() }

    private fun open(): SQLiteDatabase? {
        fun make() = SQLiteDatabase.openOrCreateDatabase(file, null).also {
            it.execSQL("CREATE TABLE IF NOT EXISTS cards(k TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL, thumb TEXT, at INTEGER NOT NULL)")
            // Kept to the most recently made: a card is a few hundred bytes.
            it.execSQL("DELETE FROM cards WHERE k IN (SELECT k FROM cards ORDER BY at DESC LIMIT -1 OFFSET $KEPT)")
        }
        return runCatching { file.parentFile?.mkdirs(); make() }.recoverCatching { file.delete(); make() }.getOrNull()
    }

    /**
     * The card for the article at [path] in [archive], or null when it can't be read. [targetPx] sizes its picture;
     * [sentences] and [maxChars] its summary (as [LeadLinks.parse] takes them). A remembered card whose picture file is
     * gone is made again.
     */
    fun card(archive: ZimArchive, path: String, targetPx: Int = ArticleThumbnails.TARGET_PX, sentences: Int = 2, maxChars: Int = LeadLinks.MAX_SUMMARY): Card? {
        val key = "$VERSION|${archive.uuid}|$path|$targetPx|$sentences|$maxChars"
        (recent.get(key) ?: stored(key))?.takeIf { it.thumbnail == null || File(it.thumbnail).isFile }?.let { card ->
            recent.put(key, card)
            // Shown again: the thumbnails' pruning keeps the ones in use.
            card.thumbnail?.let { File(it).setLastModified(System.currentTimeMillis()) }
            return card
        }
        val resolved = archive.resolvePath(path) ?: return null
        val item = archive.read(resolved)?.takeIf { it.mimeType.startsWith("text/html") } ?: return null
        // Only the top of the article: its lead, and as far as the picture rule looks.
        val html = ArticleThumbnails.head(String(item.bytes, Charsets.UTF_8))
        val lead = LeadLinks.parse(html, resolved, sentences, maxChars)
        val card = Card(lead.title, lead.summary, runCatching { thumbs.thumbnail(archive, resolved, html, targetPx) }.getOrNull())
        recent.put(key, card)
        runCatching { db?.execSQL("INSERT OR REPLACE INTO cards VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(key, card.title, card.summary, card.thumbnail, System.currentTimeMillis())) }
        return card
    }

    private fun stored(key: String): Card? = runCatching {
        db?.rawQuery("SELECT title, summary, thumb FROM cards WHERE k = ?", arrayOf(key))?.use { c ->
            if (c.moveToFirst()) Card(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2)) else null
        }
    }.getOrNull()

    private companion object {
        /** Raised when the picture rule or the summary's rule changes: cards made before are then made again. */
        const val VERSION = 1
        const val RECENT = 800
        const val KEPT = 20_000
    }
}
