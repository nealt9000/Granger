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
        // An online card is made again after a week: live Wikipedia moves (Plan O1 Task 6).
        val age = if (archive.remote) "|w${System.currentTimeMillis() / WEEK_MS}" else ""
        val key = "$VERSION|${archive.uuid}$age|$path|$targetPx|$sentences|$maxChars"
        (recent.get(key) ?: stored(key))?.takeIf { it.thumbnail == null || File(it.thumbnail).isFile }?.let { card ->
            recent.put(key, card)
            // Shown again: the thumbnails' pruning keeps the ones in use.
            card.thumbnail?.let { File(it).setLastModified(System.currentTimeMillis()) }
            return card
        }
        if (archive is app.granger.online.OnlineWikipediaArchive) return online(archive, path, key, targetPx, sentences, maxChars)
        val resolved = archive.resolvePath(path) ?: return null
        val item = archive.read(resolved)?.takeIf { it.mimeType.startsWith("text/html") } ?: return null
        // Only the top of the article: its lead, and as far as the picture rule looks.
        val html = ArticleThumbnails.head(String(item.bytes, Charsets.UTF_8))
        val lead = LeadLinks.parse(html, resolved, sentences, maxChars)
        val card = Card(lead.title, lead.summary, runCatching { thumbs.thumbnail(archive, resolved, html, targetPx) }.getOrNull())
        return kept(key, card)
    }

    /**
     * An online article's card from Wikimedia's summary (Plan O1 Task 6): one small request, never the whole article
     * (O0 found the topic page fetching it twice for its header). The picture is the summary's, asked at [targetPx].
     */
    private fun online(archive: app.granger.online.OnlineWikipediaArchive, path: String, key: String, targetPx: Int, sentences: Int, maxChars: Int): Card? {
        val s = archive.summary(path) ?: return null
        val picture = s.thumbnail?.let { sized(it, targetPx) }?.let { url ->
            runCatching { thumbs.picture(archive, app.granger.online.OnlineWikipediaArchive.IMG + url.substringAfter("://"), targetPx) }.getOrNull()
        }
        return kept(key, Card(s.title, LeadLinks.summaryOf(s.extract, sentences, maxChars), picture))
    }

    private fun kept(key: String, card: Card): Card {
        recent.put(key, card)
        runCatching { db?.execSQL("INSERT OR REPLACE INTO cards VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(key, card.title, card.summary, card.thumbnail, System.currentTimeMillis())) }
        return card
    }

    private fun stored(key: String): Card? = runCatching {
        db?.rawQuery("SELECT title, summary, thumb FROM cards WHERE k = ?", arrayOf(key))?.use { c ->
            if (c.moveToFirst()) Card(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2)) else null
        }
    }.getOrNull()

    companion object {
        private const val WEEK_MS = 7L * 24 * 3600 * 1000

        /** A Wikimedia thumbnail URL at [px] wide (`…/330px-Name.jpg` → `…/900px-Name.jpg`); others as they are. */
        fun sized(url: String, px: Int): String {
            // Wikimedia serves fixed thumbnail steps; asking any other width costs it a render.
            val step = THUMB_STEPS.firstOrNull { it >= px } ?: THUMB_STEPS.last()
            return url.replace(Regex("/(\\d+)px-([^/]+)$")) { m -> if (m.groupValues[1].toInt() >= step) m.value else "/${step}px-${m.groupValues[2]}" }
        }

        private val THUMB_STEPS = listOf(250, 330, 500, 960, 1280)

        /** Raised when the picture rule or the summary's rule changes: cards made before are then made again. */
        private const val VERSION = 1
        private const val RECENT = 800
        private const val KEPT = 20_000
    }
}
