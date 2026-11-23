package app.granger.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.granger.BookTarget
import app.granger.core.library.BookHit

/**
 * The search field's text as Compose state, written synchronously on every keystroke. The field must not read its value
 * from a flow: a value that lags a dispatch behind the keyboard is pushed back into the field and scrambles fast typing
 * (seen on the Pixel 6, 2026-10-17).
 */
class ComposeSearchFieldText : SearchFieldText {
    override var value by mutableStateOf("")
}

/** Remembers the current query, opens (or reuses) the book's tab, and returns it with the place to open at. */
suspend fun SearchViewModel.openBook(hit: BookHit): Pair<Long, BookTarget> =
    openBookTab(hit) to BookTarget(hit.href, hit.progression, hit.quote)
