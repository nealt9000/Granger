package app.granger.online

import app.granger.core.library.KeptArticle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Updates for kept articles (Plan O5 Task 4; builder's call 6): at most once a day while online, the kept articles'
 * revisions are checked against live Wikipedia, 20 a request, and the ones behind are listed. Nothing updates on its own.
 */
class KeptUpdates(
    private val revisions: (lang: String, titles: List<String>) -> Map<String, Long>,
    private val lastCheck: () -> Long,
    private val setLastCheck: (Long) -> Unit,
    private val clock: () -> Long = { app.granger.screens.nowMillis() },
) {
    private val behind = MutableStateFlow<List<KeptArticle>>(emptyList())
    val updates: StateFlow<List<KeptArticle>> = behind.asStateFlow()

    /** Checks [kept] when a day has passed since the last check (or [force]); blocking. */
    fun check(kept: List<KeptArticle>, online: Boolean, force: Boolean = false) {
        if (!online || kept.isEmpty()) return
        if (!force && clock() - lastCheck() < DAY_MS) return
        behind.value = behindOf(kept) { lang, titles -> revisions(lang, titles) }
        setLastCheck(clock())
    }

    /** One was updated or removed: it leaves the list. */
    fun done(lang: String, title: String) { behind.value = behind.value.filterNot { it.lang == lang && it.title == title } }

    companion object {
        const val DAY_MS = 24L * 3600 * 1000

        /** The kept articles whose live revision is newer than the one kept (pure, for tests); one with none kept is skipped. */
        fun behindOf(kept: List<KeptArticle>, live: (String, List<String>) -> Map<String, Long>): List<KeptArticle> =
            kept.filter { it.revision != null }.groupBy { it.lang }.flatMap { (lang, articles) ->
                val now = live(lang, articles.map { it.title })
                articles.filter { a -> now[a.title]?.let { it > a.revision!! } == true }
            }
    }
}
