package app.granger.ebook

import app.granger.InstalledSidecar
import app.granger.core.library.BookLinks
import app.granger.core.library.BookMention
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An article's opening for a link's card: its title, first lines and cached picture. */
data class ArticleLead(val title: String, val sentence: String, val thumbnail: String?)

/**
 * The app's [BookLinkSource] (Plan LL1 Task 8). A link is drawn only when an installed Wikipedia has its article, found
 * through the packs' QID indexes in Wander's order, whole encyclopedias in the library's languages first (Decision 6),
 * so every link drawn opens. Blocking reads; the reader calls it on IO.
 */
class ReferenceLinksSource(
    /** The open book's installed links file and the sha256 of the EPUB it must be built from; null when it has none. */
    private val linksFile: suspend (bookId: String) -> InstalledSidecar?,
    /** Each installed QID index by pack id, best pack first. */
    private val qidFiles: suspend () -> List<Pair<String, InstalledSidecar>>,
    /** The article [path] in [packId], opened for its card; null when it can't be read. */
    private val lead: (packId: String, path: String) -> ArticleLead?,
) : BookLinkSource {
    private val lock = Mutex()
    private var opened: Pair<List<Pair<String, InstalledSidecar>>, QidIndexes>? = null

    /** The QID indexes now installed, opened once and again whenever the set changes. */
    private suspend fun indexes(): QidIndexes = lock.withLock {
        val files = qidFiles()
        opened?.takeIf { it.first == files }?.let { return it.second }
        opened?.second?.close()
        val fresh = QidIndexes(files.mapNotNull { (packId, s) -> QidIndex.open(s.file, packId, s.packSha256) })
        opened = files to fresh
        fresh
    }

    override suspend fun mentions(bookId: String, level: LinkLevel): List<BookMention> {
        if (level == LinkLevel.OFF) return emptyList()
        val file = linksFile(bookId) ?: return emptyList()
        val all = BookLinks.open(file.file, file.packSha256)?.use { it.mentions(level.maxTier) } ?: return emptyList()
        if (all.isEmpty()) return all
        val qids = indexes()
        if (qids.isEmpty) return emptyList()
        val known = all.mapTo(HashSet()) { it.qid }.filterTo(HashSet()) { qids.articlesOf(it).isNotEmpty() }
        return all.filter { it.qid in known }
    }

    override suspend fun peek(qid: Long): LinkPeek? {
        for (article in indexes().articlesOf(qid)) {
            val l = lead(article.packId, article.path) ?: continue
            return LinkPeek(qid, article.packId, article.path, l.title, l.sentence, l.thumbnail)
        }
        return null
    }
}
