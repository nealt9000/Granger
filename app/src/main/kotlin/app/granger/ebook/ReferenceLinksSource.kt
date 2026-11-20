package app.granger.ebook

import app.granger.core.library.open
import app.granger.InstalledSidecar
import app.granger.core.library.BookLinks
import app.granger.core.library.BookMention
import app.granger.core.library.LinksFile
import app.granger.core.library.MentionIndex
import app.granger.core.library.QidIndex
import app.granger.core.library.QidIndexes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /**
     * Where opening a book brings [index] into step, so a link's card never waits on the copy (5 s for the 185 Clásicos
     * on a Pixel 6); null leaves it to the first footer.
     */
    private val background: CoroutineScope? = null,
    /** A Wikipedia pack's language (`en`), for one with no QID index of its own; null for other packs. */
    private val wikipediaLanguage: suspend (packId: String) -> String? = { null },
    /** A book's language as two letters, or null when unknown. */
    private val bookLanguage: suspend (bookId: String) -> String? = { null },
    /** The adult-content filter, pointed at each fresh set of indexes, whose ranked lists then leave its hidden items out. */
    private val contentFilter: app.granger.core.library.ContentFilter? = null,
) : BookLinkSource, ArticleFooterSource {
    private val lock = Mutex()
    private var opened: Pair<List<Pair<String, InstalledSidecar>>, QidIndexes>? = null

    /** The QID indexes now installed, opened once and again whenever the set changes (the topic page reads them too). */
    suspend fun qidIndexes(): QidIndexes = indexesNow.get { indexes() }

    /** Kept for a moment: a page asks many times as it loads, and each ask listed the index files again. */
    private val indexesNow = app.granger.Briefly<QidIndexes>()

    private suspend fun indexes(): QidIndexes = lock.withLock {
        val files = qidFiles()
        opened?.takeIf { it.first == files }?.let { return it.second }
        opened?.second?.close()
        val fresh = QidIndexes(files.mapNotNull { (packId, s) -> QidIndex.open(s.file, packId, s.packSha256.ifEmpty { null }, s.packName) })
        contentFilter?.let { f ->
            fresh.hide = { f.hides(it) }
            fresh.noPicture = { f.noPicture(it) }
            f.indexes = fresh
        }
        opened = files to fresh
        fresh
    }

    /** The shelf's books with a Wikidata work (from their links files), for a subject's In your books (Plan EX Task 3). */
    suspend fun booksWithWorks(): List<Pair<LibraryBook, Long>> =
        synced().works().mapNotNull { (id, work) -> bookOf(id)?.let { it to work } }

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
        background?.launch(Dispatchers.IO) { synced() }
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
            // The index as it stands: a card doesn't wait for a sync still copying books in.
            val book = index.worksNow().entries.firstOrNull { it.value == qid }?.let { bookOf(it.key) }
            return LinkPeek(qid, article.packId, article.path, l.title, l.sentence, l.thumbnail, book)
        }
        return null
    }

    override suspend fun footer(packId: String, path: String): ArticleFooter = footer(packId, path, null, null)

    override suspend fun footer(packId: String, path: String, languages: Set<String>?, first: String?): ArticleFooter {
        val all = indexes()
        // A Wikipedia pack without its own index finds the page by title in another of its language.
        val qid = all.qidOf(packId, path)
            ?: (if (all.has(packId)) null else wikipediaLanguage(packId)?.let { all.qidOfTitle(path, it) })
            ?: return ArticleFooter.NONE
        val books = synced()
        val works = books.works()
        val thisWork = works.filterValues { it == qid }.keys
        val counts = if (languages == null && first == null) books.counts(qid) else {
            val tagged = books.counts(qid).map { (bookId, n) -> Triple(bookId, n, bookLanguage(bookId)) }
                .filter { (_, _, language) -> languages == null || language == null || language in languages }
            // Stable: within each side the most mentions first, as counts come.
            tagged.sortedBy { (_, _, language) -> if (first != null && language == first) 0 else 1 }.map { (bookId, n, _) -> bookId to n }
        }
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
