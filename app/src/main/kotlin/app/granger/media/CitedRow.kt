package app.granger.media

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.granger.theme.GrangerColors
import kotlinx.coroutines.delay

/** How long a cited song or chapter stays marked after its page opens (as a Research source card flashes). */
const val CITED_MARK_MS = 2_000L

/**
 * Scrolls [list] to the cited row at [index] once, when it's known, and says whether the row is still marked. The page
 * opened from a Research citation (research spec §7); a null or negative [index] marks nothing. Saved, so turning
 * the phone doesn't scroll back to it.
 */
@Composable
fun citedMark(list: LazyListState, index: Int?): Boolean {
    var shown by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(index) {
        if (index == null || index < 0 || shown) return@LaunchedEffect
        shown = true
        marked = true
        list.animateScrollToItem(index)
        delay(CITED_MARK_MS)
        marked = false
    }
    return marked
}

/** The cited row's tint while [marked]. */
fun Modifier.citedTint(marked: Boolean): Modifier = if (marked) background(GrangerColors.Flame.copy(alpha = 0.15f)) else this
