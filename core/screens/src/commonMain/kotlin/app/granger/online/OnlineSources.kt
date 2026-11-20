package app.granger.online

import app.granger.core.online.WikimediaClient
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import app.granger.core.library.ConcurrentCache
import app.granger.core.text.percentDecoded

/**
 * The online sources (Plan O1 Task 4, builder's calls 1 and 2): Wikipedia online in each knowledge language, while
 * [enabled]. They aren't packs (the pack table, the Library screen and storage stay about files on the phone), but the
 * places that name or pick a source of articles (Continue, the rail, history, Home's encyclopedias, the topic page)
 * see them through [known], as rows shaped like a pack's with an empty file path.
 */
class OnlineSources(
    private val wiki: WikimediaClient,
    private val languages: Flow<List<String>>,
    private val languagesNow: () -> List<String>,
    private val enabled: () -> Boolean,
    /** A kept copy's page when an online article can't be fetched ([OnlineWikipediaArchive]'s fallback). */
    private val fallback: (lang: String, title: String) -> app.granger.core.zim.ZimItem? = { _, _ -> null },
    /** A random article of the index bound to a source ([OnlineWikipediaArchive]'s Wander start). */
    private val indexRandom: (packId: String) -> String? = { null },
    /** Kept articles and Saved for now (Plan O2). */
    private val stores: OfflineStores? = null,
    /** Timings in the log (debug builds). */
    private val timings: Boolean = false,
) {
    private val archives = ConcurrentCache<String, OnlineWikipediaArchive>()

    /** The online archives by source id, for the library's `archive()`. Never blocks. */
    fun archives(): Map<String, OnlineWikipediaArchive> =
        if (!enabled()) emptyMap() else languagesNow().associate { lang ->
            OnlineWikipediaArchive.packName(lang) to archives.computeIfAbsent(lang) { OnlineWikipediaArchive(lang, wiki, fallback, indexRandom = { indexRandom(OnlineWikipediaArchive.packName(lang)) }, stores = stores, timings = timings) }
        }

    fun archive(lang: String): OnlineWikipediaArchive? = archives()[OnlineWikipediaArchive.packName(lang)]

    /** The online sources as pack-shaped rows. */
    val packs: Flow<List<Pack>> = languages.map { langs -> if (enabled()) langs.map(::row) else emptyList() }

    /** The packs on the phone, then the online sources. */
    fun known(installed: Flow<List<Pack>>): Flow<List<Pack>> = combine(installed, packs) { a, b -> a + b }

    /**
     * A Wikipedia web address as the reader's URL for the same article online (`https://es.wikipedia.org/wiki/Luna`),
     * when that language is on; null otherwise. Links to other Wikipedias, edit pages and anything else stay external.
     */
    fun inApp(url: String): String? {
        if (!enabled()) return null
        val m = WIKI_URL.matchEntire(url) ?: return null
        val lang = m.groupValues[1]
        if (lang !in languagesNow()) return null
        val title = percentDecoded(m.groupValues[2]) ?: return null
        if (title.isBlank() || NAMESPACE.containsMatchIn(title)) return null
        val fragment = m.groupValues[3].takeIf { it.isNotEmpty() }
        return app.granger.core.zim.ZimUrls.forEntry(OnlineWikipediaArchive.packName(lang), title.replace(' ', '_'), fragment)
    }

    companion object {
        private val WIKI_URL = Regex("^https?://([a-z]{2,3})(?:\\.m)?\\.wikipedia\\.org/wiki/([^?#]+)(?:\\?[^#]*)?(?:#(.*))?$")
        /** Special:, File:, Talk:… pages aren't articles. */
        private val NAMESPACE = Regex(
            "^(Special|File|Image|Media|Talk|User|User_talk|Wikipedia|Help|Template|Category|Portal|Draft|Module|MediaWiki|" +
                "Especial|Archivo|Imagen|Usuario|Ayuda|Plantilla|Categoría|Discusión|Wikiproyecto|Portal)[ _]?(talk|discusión)?:",
            RegexOption.IGNORE_CASE,
        )

        fun row(lang: String) = Pack(
            id = OnlineWikipediaArchive.packName(lang), filePath = "", title = "Wikipedia", description = "", // l10n-ignore: the source's name, a brand
            language = lang, date = "", sizeBytes = 0, hasFulltextIndex = true, status = PackStatus.OK, addedAt = 0,
            name = OnlineWikipediaArchive.packName(lang),
        )

        fun isOnline(packId: String) = OnlineWikipediaArchive.languageOf(packId) != null
    }
}

/** The first of each list, then the second of each, and so on: [[a1, a2], [b1]] → [a1, b1, a2]. */
fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = ArrayList<T>(lists.sumOf { it.size })
    var i = 0
    while (lists.any { i < it.size }) { for (l in lists) if (i < l.size) out += l[i]; i++ }
    return out
}
