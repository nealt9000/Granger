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

/** Searches every OK pack. Plan 5 replaces the implementation with `:core:search` sources. */
interface LibrarySearch {
    suspend fun titles(query: String): List<SearchHit>
    suspend fun fullText(query: String): List<SearchHit>

    /** Full-text hits inside books; empty where books aren't searchable. */
    suspend fun books(query: String): List<BookHit> = emptyList()
}

class ZimLibrarySearch(
    private val library: Library,
    private val bookIndex: BookIndex? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val titlesPerPack: Int = TITLE_LIMIT,
    private val fullTextLimit: Int = FULL_TEXT_LIMIT,
    private val titleLimit: Int = TITLE_LIMIT,
) : LibrarySearch {
    // Pack by pack, in library order; Search shows the first few and keeps the rest behind Show all.
    override suspend fun titles(query: String): List<SearchHit> =
        across(query) { it.suggest(query, titlesPerPack) }.take(titleLimit).map { it.second }

    override suspend fun fullText(query: String): List<SearchHit> =
        across(query) { if (it.hasFulltextIndex) it.search(query, fullTextLimit) else emptyList() }
            .sortedByDescending { it.first.score }
            .take(fullTextLimit)
            .map { it.second }

    override suspend fun books(query: String): List<BookHit> = bookIndex?.search(query) ?: emptyList()

    /** Runs [run] on each OK pack; a pack that fails is skipped so the others still answer. */
    private suspend fun across(query: String, run: (ZimArchive) -> List<ZimHit>): List<Pair<ZimHit, SearchHit>> {
        if (query.isBlank()) return emptyList()
        val packs = library.packs.first().filter { it.status == PackStatus.OK }
        return withContext(io) {
            packs.flatMap { pack ->
                val archive = library.archive(pack.id) ?: return@flatMap emptyList()
                val hits = try { run(archive) } catch (e: Exception) { emptyList() }
                // Title suggestions are often redirects; point them at the article itself so the
                // reader keys bookmarks and positions by the real path, and list each article once.
                hits.map { hit -> hit.copy(path = archive.resolvePath(hit.path) ?: hit.path) }
                    .distinctBy { it.path }
                    .map { hit -> hit to SearchHit(pack.id, pack.title, hit.path, hit.title, hit.snippet) }
            }
        }
    }

    companion object {
        /** The most titles returned, over every pack (and from any one pack). */
        const val TITLE_LIMIT = 50
        const val FULL_TEXT_LIMIT = 30
    }
}
