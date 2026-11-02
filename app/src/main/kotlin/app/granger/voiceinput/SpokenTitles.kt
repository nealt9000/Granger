package app.granger.voiceinput

import app.granger.core.library.LibraryNameRow
import app.granger.core.library.LibraryNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The library's names as a person says them (Plan A1), for the spoken title matcher. Kept up to date from the
 * library's tables, so a pack or book added today is matched today (the A0 spike's list was two days stale). Only
 * read while something is watching, and kept 5 s after the last watcher goes.
 */
class SpokenTitles(rows: Flow<List<LibraryNameRow>>, scope: CoroutineScope) {
    val names: StateFlow<List<String>> = rows
        .map(LibraryNames::from)
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
