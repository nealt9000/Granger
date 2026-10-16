package app.granger.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Library
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One bookmark tab on the rail. [initial] fills the letter tile, since ZIM articles have no covers. */
data class RailTabUi(
    val tabId: Long,
    val title: String,
    /** The pack's title. */
    val subtitle: String,
    val initial: Char,
    /** False when the pack is missing, damaged or gone; the tile is greyed out. */
    val available: Boolean,
    /** The tab the reader is showing; outlined in flame. */
    val current: Boolean,
)

data class RailUi(val tabs: List<RailTabUi>, val recentSearches: List<String>)

/** The rail's Bookmarks and Recent sections. [currentTabId] is the tab on screen, null off the reader. */
class RailViewModel(
    private val sessions: ReadingSessions,
    library: Library,
    currentTabId: StateFlow<Long?>,
) : ViewModel() {
    val ui: StateFlow<RailUi> = combine(
        sessions.railTabs(),
        library.packs,
        currentTabId,
        sessions.recentSearches(),
    ) { tabs, packs, current, searches ->
        val byId = packs.associateBy { it.id }
        RailUi(
            tabs = tabs.map { tab ->
                val pack = byId[tab.packId]
                RailTabUi(
                    tabId = tab.id,
                    title = tab.title,
                    subtitle = pack?.title ?: HistoryViewModel.REMOVED_PACK,
                    initial = initialOf(tab.title),
                    available = pack?.status == PackStatus.OK,
                    current = tab.id == current,
                )
            },
            recentSearches = searches,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RailUi(emptyList(), emptyList()))

    /** Long-press "Remove from bookmarks": off the rail, still in History. */
    fun closeTab(tabId: Long) {
        viewModelScope.launch { sessions.closeTab(tabId) }
    }

    private fun initialOf(title: String): Char = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
}
