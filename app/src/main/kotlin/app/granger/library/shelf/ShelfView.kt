package app.granger.library.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.settings.RailSide
import app.granger.shell.LocalRailSide
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The letter strip's width, which the list keeps clear on its side. */
private val StripWidth = 28.dp

/** The height each letter needs; below it the strip drops letters for dots. */
private val LetterHeight = 16.dp

/** A list row's cover. */
val ShelfCoverSize = 48.dp

/**
 * A shelf as an A–Z list (Plan LV Decision 2): [header] items of the screen's own first (a find field, sort chips),
 * then [sections] under sticky letter headers when lettered, with the [LetterStrip] on the edge away from the rail.
 * [row] draws one item; it keeps the shelf's own tap, long-press and dimming.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> ShelfList(
    sections: ShelfSections<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
    contentType: String = "item",
    header: LazyListScope.() -> Unit = {},
    row: @Composable LazyItemScope.(T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stripSide = stripSide()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding.withStrip(sections.lettered, stripSide)) {
            header()
            for (section in sections.sections) {
                if (sections.lettered) stickyHeader(key = "letter:${section.letter}", contentType = "letter") { LetterHeader(section.letter) }
                items(section.items.size, key = { section.items[it].key }, contentType = { contentType }) { i -> row(section.items[i].value) }
            }
        }
        if (sections.lettered) LetterStrip(
            sections.letters,
            onLetter = { letter ->
                val at = sections.headerIndex[letter] ?: return@LetterStrip
                // The screen's own header items come first; they're whatever the list holds beyond the shelf's rows.
                val lead = (state.layoutInfo.totalItemsCount - sections.rowCount).coerceAtLeast(0)
                scope.launch { state.scrollToItem(lead + at) }
            },
            side = stripSide,
            modifier = Modifier.align(if (stripSide == RailSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

/**
 * A shelf as a cover grid (Plan LV Decision 2): [header] items span the width, then [sections] under full-width letter
 * headers when lettered, cells at least [cellMin] wide, and the [LetterStrip] as in [ShelfList].
 */
@Composable
fun <T> ShelfGrid(
    sections: ShelfSections<T>,
    cellMin: Dp,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 16.dp,
    contentType: String = "item",
    header: LazyGridScope.() -> Unit = {},
    cell: @Composable LazyGridItemScope.(T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stripSide = stripSide()
    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cellMin),
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding.withStrip(sections.lettered, stripSide),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        ) {
            header()
            for (section in sections.sections) {
                if (sections.lettered) item(key = "letter:${section.letter}", span = { GridItemSpan(maxLineSpan) }, contentType = "letter") {
                    LetterHeader(section.letter, inGrid = true)
                }
                items(section.items.size, key = { section.items[it].key }, contentType = { contentType }) { i -> cell(section.items[i].value) }
            }
        }
        if (sections.lettered) LetterStrip(
            sections.letters,
            onLetter = { letter ->
                val at = sections.headerIndex[letter] ?: return@LetterStrip
                val lead = (state.layoutInfo.totalItemsCount - sections.rowCount).coerceAtLeast(0)
                scope.launch { state.scrollToItem(lead + at) }
            },
            side = stripSide,
            modifier = Modifier.align(if (stripSide == RailSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart),
        )
    }
}

/** The strip goes on the edge away from the rail. */
@Composable
private fun stripSide(): RailSide = if (LocalRailSide.current == RailSide.RIGHT) RailSide.LEFT else RailSide.RIGHT

/** This padding with room for the strip on its side, when there is one. */
@Composable
private fun PaddingValues.withStrip(strip: Boolean, side: RailSide): PaddingValues {
    if (!strip) return this
    val start = calculateStartPadding(LayoutDirection.Ltr)
    val end = calculateEndPadding(LayoutDirection.Ltr)
    return PaddingValues(
        start = if (side == RailSide.LEFT) maxOf(start, StripWidth) else start,
        top = calculateTopPadding(),
        end = if (side == RailSide.RIGHT) maxOf(end, StripWidth) else end,
        bottom = calculateBottomPadding(),
    )
}

/** A letter's header: the accent colour on the page's own background, so a sticky one covers the rows it passes. */
@Composable
private fun LetterHeader(letter: String, inGrid: Boolean = false) {
    Text(
        letter,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = if (inGrid) 0.dp else 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/**
 * The letters present, spread down the shelf's edge (Plan LV Decision 4). Tap or drag jumps to a letter's header
 * ([onLetter]); while dragging, a bubble beside the thumb shows the letter, with a light tick on each change. When the
 * letters don't fit, every other one becomes a dot; the drag still reaches every letter. Each letter is a TalkBack
 * button, "Jump to M".
 */
@Composable
fun LetterStrip(letters: List<String>, onLetter: (String) -> Unit, side: RailSide, modifier: Modifier = Modifier) {
    if (letters.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val jump by rememberUpdatedState(onLetter)
    var dragging by remember { mutableStateOf<String?>(null) }
    var thumbY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight().width(StripWidth)) {
        val fits = (maxHeight / LetterHeight).toInt().coerceAtLeast(1)
        val shown = stripEntries(letters, fits)
        val heightPx = constraints.maxHeight.toFloat()
        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp)
                .pointerInput(letters) {
                    fun letterAt(y: Float): String {
                        val fraction = (y / size.height).coerceIn(0f, 0.9999f)
                        return letters[(fraction * letters.size).toInt()]
                    }
                    fun pick(y: Float) {
                        thumbY = y
                        val letter = letterAt(y)
                        if (letter != dragging) {
                            if (dragging != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            dragging = letter
                            jump(letter)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(change.position.y)
                        }
                        dragging = null
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (entry in shown) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (entry == null) {
                        Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
                    } else {
                        Text(
                            entry,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry == dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = "Jump to $entry"
                                onClick { jump(entry); true }
                            },
                        )
                    }
                }
            }
        }
        val letter = dragging
        if (letter != null) {
            val bubble = 56.dp
            val bubblePx = with(density) { bubble.toPx() }
            val x = with(density) { if (side == RailSide.RIGHT) -(bubble + 8.dp).roundToPx() else (StripWidth + 8.dp).roundToPx() }
            val y = (thumbY - bubblePx / 2).coerceIn(0f, (heightPx - bubblePx).coerceAtLeast(0f)).roundToInt()
            Box(
                Modifier
                    // Wider than the strip: laid out unbounded from its corner, then moved beside it.
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset { IntOffset(x, y) }
                    .size(bubble)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/**
 * The strip's entries for [letters] in [fits] places: every letter when they fit, else evenly chosen letters (the
 * first and last always) with a dot (null) standing in wherever letters were dropped between them.
 */
internal fun stripEntries(letters: List<String>, fits: Int): List<String?> {
    if (letters.size <= fits) return letters
    // k letters and up to k - 1 dots between them.
    val keep = ((fits + 1) / 2).coerceAtLeast(2).coerceAtMost(letters.size)
    val picks = (0 until keep).map { i -> (i * (letters.size - 1).toDouble() / (keep - 1)).roundToInt() }.distinct()
    return buildList {
        picks.forEachIndexed { n, at ->
            if (n > 0 && at - picks[n - 1] > 1) add(null)
            add(letters[at])
        }
    }
}

/**
 * A shelf's controls (Plan LV Decision 6): [leading] (Songs' Shuffle all), the sort as a "Sort: Title" menu over
 * [sorts] when there is a choice, and the [ViewToggle] when [view] is given.
 */
@Composable
fun <S> ShelfHeader(
    sort: S,
    sorts: List<S>,
    label: (S) -> String,
    onSort: (S) -> Unit,
    modifier: Modifier = Modifier,
    view: ShelfView? = null,
    onToggleView: () -> Unit = {},
    leading: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.weight(1f))
        if (sorts.size > 1) Box {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = true }) { Text("Sort: ${label(sort)}") }
            DropdownMenu(open, { open = false }) {
                for (s in sorts) DropdownMenuItem(text = { Text(label(s)) }, onClick = { open = false; onSort(s) })
            }
        }
        if (view != null) ViewToggle(view, onToggleView)
    }
}

/** One icon button that shows the view a tap switches to: the grid icon in List, the list icon in Grid. */
@Composable
fun ViewToggle(view: ShelfView, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (view == ShelfView.LIST) {
            Icon(painterResource(R.drawable.ic_grid_view), contentDescription = "Show as grid")
        } else {
            Icon(painterResource(R.drawable.ic_view_list), contentDescription = "Show as list")
        }
    }
}

/** The other view. */
fun ShelfView.toggled(): ShelfView = if (this == ShelfView.LIST) ShelfView.GRID else ShelfView.LIST

/**
 * A list row (Plan LV Decision 2): a [ShelfCoverSize] [cover], the [title] on two lines at most, [subtitle] and an
 * optional [detail] line, and [trailing]. [dimmed] (a missing file) fades it; it still takes its long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(
    title: String,
    subtitle: String,
    cover: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    detail: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
) {
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = { Box(Modifier.size(ShelfCoverSize).alpha(if (dimmed) 0.4f else 1f), contentAlignment = Alignment.Center) { cover() } },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isEmpty() && detail == null) null else {
            {
                Column {
                    if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    detail?.invoke()
                }
            }
        },
        trailingContent = trailing,
    )
}
