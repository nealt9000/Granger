package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** One search result. [snippet] is HTML with `<b>` highlights, set only for full-text results. */
data class SearchHit(
    val packId: String,
    val packTitle: String,
    val path: String,
    val title: String,
    val snippet: String? = null,
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
) : LibrarySearch {
    // The packs take turns, each in its own order, so a big pack can't push the others below the first few or past the limit.
    // Then a title that is the query comes first, and one that starts with it next (Plan U3b Decision 10).
    override suspend fun titles(query: String): TitleResults {
        val packs = across(query) { it.suggest(query, titlesPerPack) }
        val turns = packs.maxOfOrNull { it.hits.size } ?: 0
        val all = (0 until turns).flatMap { turn -> packs.mapNotNull { it.hits.getOrNull(turn)?.second } }
        val q = SearchText.fold(query.trim())
        val ranked = all.sortedBy { hit ->
            val title = SearchText.fold(hit.title)
            when {
                title == q -> 0
                title.startsWith(q) -> 1
                else -> 2
            }
        }
        return TitleResults(ranked.take(titleLimit), more = all.size > titleLimit || packs.any { it.returned >= titlesPerPack })
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
                PackHits(found, returned = hits.size)
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
