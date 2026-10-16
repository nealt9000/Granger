package app.granger.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(private val search: LibrarySearch) : ViewModel() {
    data class State(
        val query: String = "",
        val titles: List<SearchHit> = emptyList(),
        /** Full-text hits whose article is not already in [titles]. */
        val fullText: List<SearchHit> = emptyList(),
        val searching: Boolean = false,
    )

    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            query.debounce(DEBOUNCE_MS).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) {
                    _state.update { it.copy(titles = emptyList(), fullText = emptyList(), searching = false) }
                    return@collectLatest
                }
                _state.update { it.copy(searching = true) }
                val titles = search.titles(q)
                _state.update { it.copy(titles = titles) }
                val shown = titles.map { it.packId to it.path }.toSet()
                val fullText = search.fullText(q).filterNot { (it.packId to it.path) in shown }
                _state.update { it.copy(fullText = fullText, searching = false) }
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text.trim()
        _state.update { it.copy(query = text) }
    }

    companion object {
        const val DEBOUNCE_MS = 250L
    }
}
