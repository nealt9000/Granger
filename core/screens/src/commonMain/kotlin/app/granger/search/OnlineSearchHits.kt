package app.granger.search

import app.granger.core.library.SearchHit
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.online.WikimediaClient
import app.granger.online.interleave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Wikipedia online's part of Search (Plan O1 Task 7), shared with the iPhone since Plan I7 Task 7c: its titles and its
 * full-text hits in each knowledge language, interleaved so a second language's hits aren't all behind Show all (Plan O2
 * Task 9). A pack title of "Wikipedia", or "Wikipedia · Español" when there are several languages.
 */
object OnlineSearchHits {
    /** How many of each a language is asked for. */
    const val LIMIT = 8

    /** [langs]' titles, then their pages with excerpts; [languageName] names a language in itself ("Español"). */
    suspend fun search(
        client: WikimediaClient, langs: List<String>, query: String, languageName: (String) -> String,
    ): Pair<List<SearchHit>, List<SearchHit>> = withContext(Dispatchers.IO) {
        if (langs.isEmpty()) return@withContext emptyList<SearchHit>() to emptyList()
        fun hit(lang: String, h: WikimediaClient.Hit, snippet: Boolean) = SearchHit(
            OnlineWikipediaArchive.packName(lang), if (langs.size > 1) "Wikipedia · ${languageName(lang)}" else "Wikipedia",
            h.key, h.title, snippet = if (snippet) h.excerpt else null,
        )
        coroutineScope {
            val titles = langs.map { l -> async { client.titles(l, query, LIMIT).map { hit(l, it, false) } } }
            val pages = langs.map { l -> async { client.pages(l, query, LIMIT).map { hit(l, it, true) } } }
            interleave(titles.map { it.await() }) to interleave(pages.map { it.await() })
        }
    }
}
