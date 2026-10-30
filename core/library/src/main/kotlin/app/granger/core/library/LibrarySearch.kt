package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One search result. [snippet] is HTML with `<b>` highlights, set only for full-text results. A book in Titles has its
 * id as [packId], an empty [path], its collection as [packTitle] and its [author].
 */
data class SearchHit(
    val packId: String,
    val packTitle: String,
    val path: String,
    val title: String,
    val snippet: String? = null,
    val author: String = "",
)

/** Title hits, and [more] when the search stopped short, so more titles may match than are listed. */
data class TitleResults(val hits: List<SearchHit>, val more: Boolean = false)

/** Searches every OK pack. Plan 5 replaces the implementation with `:core:search` sources. */
interface LibrarySearch {
    suspend fun titles(query: String): TitleResults
    suspend fun fullText(query: String): List<SearchHit>

    /** Full-text hits inside books; empty where books aren't searchable. */
    suspend fun books(query: String): List<BookHit> = emptyList()
}

class ZimLibrarySearch(
    private val library: Library,
    private val bookIndex: BookIndex? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val titlesPerPack: Int = TITLES_PER_PACK,
    private val fullTextLimit: Int = FULL_TEXT_LIMIT,
    private val titleLimit: Int = TITLE_LIMIT,
    /** The books Titles lists by title or author; none when null. */
    private val bookshelf: Bookshelf? = null,
    /** The sets the packs are slices of, so a smaller slice's hits don't double a bigger one's (Plan WF Decision 6). */
    private val slices: PackSlices? = null,
) : LibrarySearch {
    // The packs take turns, each in its own order, so a big pack can't push the others below the first few or past the limit.
    // Then a title that is the query comes first, and one that starts with it next (Plan U3b Decision 10). Books on the
    // shelf go ahead of pack titles of the same rank, and a book found by its author after any title containing the
    // query (Plan U2 Decision 1).
    override suspend fun titles(query: String): TitleResults {
        if (query.isBlank()) return TitleResults(emptyList())
        val books = bookTitles(query)
        val packs = across(query) { it.suggest(query, titlesPerPack) }
        val turns = packs.maxOfOrNull { it.hits.size } ?: 0
        val all = (0 until turns).flatMap { turn -> packs.mapNotNull { it.hits.getOrNull(turn)?.second } }
        val q = SearchText.fold(query.trim())
        val ranked = all.map { hit ->
            val title = SearchText.fold(hit.title)
            val rank = when {
                title == q -> BookTitleSearch.Rank.EXACT
                title.startsWith(q) -> BookTitleSearch.Rank.STARTS_WITH
                else -> null
            }
            rank to hit
        }
        // Stable: books before packs within a rank, and pack titles that are neither last, in their turn order.
        val merged = (books + ranked).sortedBy { (rank, _) -> rank?.ordinal ?: BookTitleSearch.Rank.entries.size }.map { it.second }
        return TitleResults(merged.take(titleLimit), more = merged.size > titleLimit || packs.any { it.returned >= titlesPerPack })
    }

    /** The shelf's books matching [query] by title or author, as title hits that open the book. */
    private suspend fun bookTitles(query: String): List<Pair<BookTitleSearch.Rank?, SearchHit>> {
        val shelf = bookshelf?.books?.first() ?: return emptyList()
        return BookTitleSearch.search(shelf, query).map { (rank, book) ->
            rank to SearchHit(book.id, Filing.defaultName(Filing.defaultKey(book), book), "", book.title, author = book.author)
        }
    }

    override suspend fun fullText(query: String): List<SearchHit> =
        across(query) { if (it.hasFulltextIndex) it.search(query, fullTextLimit) else emptyList() }
            .flatMap { it.hits }
            .sortedByDescending { it.first.score }
            .take(fullTextLimit)
            .map { it.second }

    override suspend fun books(query: String): List<BookHit> = bookIndex?.search(query) ?: emptyList()

    /** One pack's hits; [returned] counts them as the pack gave them, before redirects are merged. */
    private class PackHits(val hits: List<Pair<ZimHit, SearchHit>>, val returned: Int)

    /**
     * Runs [run] on each OK pack, in library order; a pack that fails is skipped so the others still answer. Index pages
     * (user and tag lists, [IndexPages]) are left out, and titles lose their site ([PageTitles]) before they're ranked.
     */
    private suspend fun across(query: String, run: (ZimArchive) -> List<ZimHit>): List<PackHits> {
        if (query.isBlank()) return emptyList()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        return withContext(io) {
            packs.mapNotNull { pack ->
                val archive = library.archive(pack.id) ?: return@mapNotNull null
                val hits = try { run(archive) } catch (e: Exception) { emptyList() }
                // Title suggestions are often redirects; point them at the article itself so the
                // reader keys bookmarks and positions by the real path, and list each article once.
                val found = hits.map { hit -> hit.copy(path = archive.resolvePath(hit.path) ?: hit.path) }
                    .distinctBy { it.path }
                    .filterNot { IndexPages.isIndex(it.path) }
                    .map { hit -> hit to SearchHit(pack.id, pack.title, hit.path, PageTitles.clean(hit.title, pack.title), hit.snippet) }
                PackHits(SliceDedupe.apply(found, packs, slices, library::archive, { it.second.packId }, { it.second.path }), returned = hits.size)
            }
        }
    }

    companion object {
        /** The most titles returned over every pack. */
        const val TITLE_LIMIT = 50
        /**
         * The titles asked of each pack: enough that a library of one or two packs has more than Search's first few to
         * show, without asking every pack for the whole [TITLE_LIMIT] on each keystroke.
         */
        const val TITLES_PER_PACK = 20
        const val FULL_TEXT_LIMIT = 30
    }
}
