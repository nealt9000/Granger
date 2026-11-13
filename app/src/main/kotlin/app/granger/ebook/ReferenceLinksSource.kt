package app.granger.ebook

import app.granger.InstalledSidecar
import app.granger.core.library.BookLinks
import app.granger.core.library.BookMention
import app.granger.core.library.LinksFile
import app.granger.core.library.MentionIndex
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** An article's opening for a link's card: its title, first lines and cached picture. */
data class ArticleLead(val title: String, val sentence: String, val thumbnail: String?)

/** A book on the phone, as the Linked Library shows it: to open, with its title and cover. */
data class LibraryBook(val bookId: String, val title: String, val cover: String?)

/** One installed Classic that mentions an article's subject: how often, and its first few passages (Task 9). */
data class InYourBook(val book: LibraryBook, val count: Int, val passages: List<BookMention>)

/**
 * What the web reader shows under an article (Plan LL1 Task 9): the article's work when it's on the phone, the
 * Classics that mention its subject, and the installed books by its subject when that's an author.
 */
data class ArticleFooter(
    val thisWork: List<LibraryBook>,
    /** The books mentioning it most, at most [ReferenceLinksSource.BOOKS_SHOWN]. */
    val inBooks: List<InYourBook>,
    val byAuthor: List<LibraryBook>,
    /** How many installed books mention it in all (London is in 290 of the 622 Classics). */
    val inBooksTotal: Int = inBooks.size,
) {
    val isEmpty: Boolean get() = thisWork.isEmpty() && inBooks.isEmpty() && byAuthor.isEmpty()

    companion object {
        val NONE = ArticleFooter(emptyList(), emptyList(), emptyList())
    }
}

/** Where the web reader gets an article's footer; the app's is [ReferenceLinksSource]. */
fun interface ArticleFooterSource {
    suspend fun footer(packId: String, path: String): ArticleFooter
}

/**
 * The Linked Library on the phone (Plan LL1 Tasks 8–9): the Classics' `.glink` files and the packs' QID indexes, read
 * together. A book's link is drawn only when an installed Wikipedia has its article, found through the QID indexes in
 * Wander's order, whole encyclopedias in the library's languages first (Decision 6), so every link drawn opens. The
 * other way round, an article's footer asks [index], every installed Classic's links in one file, kept in step with
 * the installed books. Blocking reads; called on IO.
 */
class ReferenceLinksSource(
    /** Each installed Classic's links file by bookshelf id, with the sha256 of the EPUB it must be built from. */
    private val linksFiles: suspend () -> Map<String, InstalledSidecar>,
    /** Each installed QID index by pack id, best pack first. */
    private val qidFiles: suspend () -> List<Pair<String, InstalledSidecar>>,
    /** The article [path] in [packId], opened for its card; null when it can't be read. */
    private val lead: (packId: String, path: String) -> ArticleLead?,
    /** A book on the shelves; null when it's gone. */
    private val bookOf: suspend (bookId: String) -> LibraryBook?,
    /** Every installed Classic's mentions in one file, for the reverse view. */
    private val index: MentionIndex,
) : BookLinkSource, ArticleFooterSource {
    private val lock = Mutex()
    private var opened: Pair<List<Pair<String, InstalledSidecar>>, QidIndexes>? = null

    /** The QID indexes now installed, opened once and again whenever the set changes. */
    private suspend fun indexes(): QidIndexes = lock.withLock {
        val files = qidFiles()
        opened?.takeIf { it.first == files }?.let { return it.second }
        opened?.second?.close()
        val fresh = QidIndexes(files.mapNotNull { (packId, s) -> QidIndex.open(s.file, packId, s.packSha256, s.packName) })
        opened = files to fresh
        fresh
    }

    /** [index] brought into step with the installed links files (a no-op when they haven't changed). */
    private suspend fun synced(): MentionIndex {
        val t0 = System.nanoTime()
        val copied = index.sync(linksFiles().mapValues { (_, s) -> LinksFile(s.file, s.packSha256) })
        if (copied > 0 && app.granger.BuildConfig.DEBUG) {
            android.util.Log.d("GrangerLinks", "mention index: copied $copied books in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        return index
    }

    override suspend fun mentions(bookId: String, level: LinkLevel): List<BookMention> {
        if (level == LinkLevel.OFF) return emptyList()
        val file = linksFiles()[bookId] ?: return emptyList()
        val all = BookLinks.open(file.file, file.packSha256)?.use { it.mentions(level.maxTier) } ?: return emptyList()
        if (all.isEmpty()) return all
        val qids = indexes()
        if (qids.isEmpty) return emptyList()
        val known = all.mapTo(HashSet()) { it.qid }.filterTo(HashSet()) { qids.articlesOf(it).isNotEmpty() }
        return all.filter { it.qid in known }
    }

    override suspend fun peek(qid: Long, language: String?): LinkPeek? {
        val qids = indexes()
        for (article in qids.articlesOf(qid, language)) {
            val l = lead(article.packId, article.path) ?: continue
            val book = synced().works().entries.firstOrNull { it.value == qid }?.let { bookOf(it.key) }
            return LinkPeek(qid, article.packId, article.path, l.title, l.sentence, l.thumbnail, book)
        }
        return null
    }

    override suspend fun footer(packId: String, path: String): ArticleFooter {
        val qid = indexes().qidOf(packId, path) ?: return ArticleFooter.NONE
        val books = synced()
        val works = books.works()
        val thisWork = works.filterValues { it == qid }.keys
        val counts = books.counts(qid)
        val inBooks = ArrayList<InYourBook>()
        for ((bookId, n) in counts) {
            if (inBooks.size == BOOKS_SHOWN) break
            bookOf(bookId)?.let { inBooks += InYourBook(it, n, books.passages(bookId, qid, PASSAGES)) }
        }
        // An author's works: P800 on the author, and every work naming them in P50.
        val qids = indexes()
        val written = (qids.linksOf(qid, "P800") + qids.itemsWith("P50", "Q$qid").map { "Q$it" })
            .mapNotNullTo(HashSet()) { it.removePrefix("Q").toLongOrNull() }
        val byAuthor = works.filter { (bookId, work) -> work in written && bookId !in thisWork }.keys
        return ArticleFooter(
            thisWork.mapNotNull { bookOf(it) }.sortedBy { it.title },
            inBooks,
            byAuthor.mapNotNull { bookOf(it) }.sortedBy { it.title },
            inBooksTotal = if (inBooks.size < BOOKS_SHOWN) inBooks.size else counts.size,
        )
    }

    companion object {
        const val PASSAGES = 3

        /** Books listed under *In your books*; the rest are counted. */
        const val BOOKS_SHOWN = 8
    }
}
