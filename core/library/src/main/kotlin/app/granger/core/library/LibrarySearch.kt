package app.granger.core.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/** Searches every OK pack and the text inside books ([TitleSearch] and [PackSearch], shared with the iPhone since Plan I5). */
interface LibrarySearch : TitleSearch

class ZimLibrarySearch(
    library: Library,
    bookIndex: BookIndex? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    titlesPerPack: Int = TITLES_PER_PACK,
    fullTextLimit: Int = FULL_TEXT_LIMIT,
    titleLimit: Int = TITLE_LIMIT,
    /** The books Titles lists by title or author; none when null. */
    bookshelf: Bookshelf? = null,
    /** The sets the packs are slices of, so a smaller slice's hits don't double a bigger one's (Plan WF Decision 6). */
    slices: PackSlices? = null,
    /** The library's languages (Settings, two letters); null: all. */
    languages: suspend () -> Set<String>? = { null },
) : PackSearch(
    library, io, titlesPerPack, fullTextLimit, titleLimit, bookshelf?.let { shelf -> suspend { shelf.books.first() } },
    { book, packs -> Filing.defaultName(Filing.defaultKey(book), book, packs) }, slices, languages, bookIndex,
), LibrarySearch {
    companion object {
        const val ENOUGH = PackSearch.ENOUGH
        const val TITLE_LIMIT = PackSearch.TITLE_LIMIT
        const val TITLES_PER_PACK = PackSearch.TITLES_PER_PACK
        const val FULL_TEXT_LIMIT = PackSearch.FULL_TEXT_LIMIT
        const val NEAR_MAX_WORDS = PackSearch.NEAR_MAX_WORDS
        const val FUZZY_PER_PACK = PackSearch.FUZZY_PER_PACK
        const val PARALLEL_PACKS = PackSearch.PARALLEL_PACKS

        fun keywordQuery(query: String): String? = PackSearch.keywordQuery(query)
    }
}
