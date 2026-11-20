package app.granger.core.library

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
    /** The same title from other packs or the shelf, listed under this one's row (Plan SQ: one row per title). */
    val also: List<SearchHit> = emptyList(),
)

/**
 * Title hits, and [more] when the search stopped short, so more titles may match than are listed. The first [ranked] hits
 * match the query exactly or begin with it; `LibrarySearch.nearTitles` go after those, before the rest.
 */
data class TitleResults(val hits: List<SearchHit>, val more: Boolean = false, val ranked: Int = 0) {
    /** [near] titles placed after the ranked ones, one row per article. */
    fun withNear(near: List<SearchHit>): List<SearchHit> =
        if (near.isEmpty()) hits else (hits.take(ranked) + near + hits.drop(ranked)).distinctBy { it.packId to it.path }
}
