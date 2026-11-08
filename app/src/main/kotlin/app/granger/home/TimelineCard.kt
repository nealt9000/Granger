package app.granger.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.granger.R
import app.granger.core.library.Lane
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.shell.rememberRailCover
import app.granger.theme.LocalGrangerExtras
import app.granger.theme.Spectral
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the timeline's controls do; [onShown] starts it the first time Home shows it. */
class TimelineActions(
    val onShown: () -> Unit = {},
    val onToggle: (Lane) -> Unit = {},
    val onAll: () -> Unit = {},
    val onWindow: (TimeScale.Window) -> Unit = {},
    val onLens: (Double) -> Unit = {},
    val onStep: (Int) -> Unit = {},
    val onSelect: (TimeMark) -> Unit = {},
    val onSearch: (String) -> Unit = {},
)

private val RowHeight = 72.dp
private val SingleRowHeight = 152.dp
private val FullRowHeight = 120.dp
private val FullSingleRowHeight = 280.dp
private const val TRACKS = 8
private val RulerHeight = 22.dp

/** The most of the screen's height the river takes, on Home and full screen (landscape; a portrait phone is well under both). */
private const val HOME_RIVER_SHARE = 0.3f
private const val FULL_RIVER_SHARE = 0.55f
private val TickGap = 64.dp
private const val LOUPE_MARKS = 4

/**
 * The Home timeline, a glance (Neal, 2026-11-08): every dated article on the phone along one river, scrubbed with one
 * finger, with the lens card's picture, ‹ › and Open. Lanes, pinch, Nearby and search live in the full-screen
 * [TimelineScreen], which [onExpand] opens on the mark the lens is on.
 */
@Composable
fun TimelineSection(ui: TimelineUi, actions: TimelineActions, onOpen: (TimeMark) -> Unit, onExpand: (TimeMark?) -> Unit = {}) {
    LaunchedEffect(Unit) { actions.onShown() }
    SectionHeading(
        stringResource(R.string.home_timeline),
        action = stringResource(R.string.home_timeline_expand),
        onAction = { onExpand(ui.selected) },
        actionTag = "timeline-expand",
    )
    Column(Modifier.fillMaxWidth().testTag("timeline"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val words = yearWords()
        if (ui.loading && ui.marks.isEmpty()) {
            Loading(reserve = true)
            return@Column
        }
        River(ui, actions, words, full = false)
        LaneKey(ui.lanes)
        ui.selected?.let { LensCard(it, ui, actions, words, onOpen, nearby = false) }
    }
}

/**
 * What the river's colours mean, on Home where there are no chips (Neal, 2026-11-08): one line of dots and names,
 * scrolled sideways when it doesn't fit.
 */
@Composable
private fun LaneKey(lanes: List<Lane>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("timeline-key"),
    ) {
        for (lane in lanes) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(laneColour(lane)))
                Text(stringResource(laneName(lane)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The full-screen Timeline (spec §5): lanes stacked by chip, pinch and pan to deep time, the loupe and Nearby. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(ui: TimelineUi, actions: TimelineActions, onOpen: (TimeMark) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { actions.onShown() }
    var searching by rememberSaveable { mutableStateOf(ui.query.isNotBlank()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_timeline)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.home_timeline_back)) } },
                actions = {
                    IconButton(
                        onClick = { if (searching) actions.onSearch(""); searching = !searching },
                        modifier = Modifier.testTag("timeline-search-toggle"),
                    ) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.home_timeline_search)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).testTag("timeline-screen"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (searching) SearchField(ui, actions)
            Chips(ui, actions)
            val words = yearWords()
            if (ui.loading && ui.marks.isEmpty()) {
                Loading()
                return@Column
            }
            River(ui, actions, words, full = true)
            ui.selected?.let { LensCard(it, ui, actions, words, onOpen, nearby = true) }
        }
    }
}

/** "Roman", "jazz": titles with every word light up on the river; the count says how many. */
@Composable
private fun SearchField(ui: TimelineUi, actions: TimelineActions) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = ui.query,
            onValueChange = actions.onSearch,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.home_timeline_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            // Search puts the keyboard away so the river and the lens card show.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("timeline-search"),
        )
        if (ui.query.isNotBlank()) {
            Text(
                if (ui.matches.isEmpty()) stringResource(R.string.home_timeline_no_matches)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.home_timeline_matches, ui.matches.size, ui.matches.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("timeline-match-count"),
            )
        }
    }
}

/**
 * While the dates load, Home keeps the room the river, key and lens card will take, so Play and Wander below don't jump
 * down when they arrive (Pixel 6, 2026-11-08: a tap meant for Play landed on the river).
 */
@Composable
private fun Loading(reserve: Boolean = false) {
    val river = minOf(SingleRowHeight, LocalConfiguration.current.screenHeightDp.dp * HOME_RIVER_SHARE - RulerHeight) + RulerHeight
    Box(if (reserve) Modifier.fillMaxWidth().height(river + LoadingExtra) else Modifier) {
        Text(
            stringResource(R.string.home_timeline_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The lane key and the lens card under Home's river, with their spacing. */
private val LoadingExtra = 108.dp

@Composable
private fun Chips(ui: TimelineUi, actions: TimelineActions) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        FilterChip(selected = ui.on.isEmpty(), onClick = actions.onAll, label = { Text(stringResource(R.string.home_timeline_all)) }, modifier = Modifier.testTag("timeline-chip-all"))
        for (lane in ui.lanes) {
            FilterChip(
                selected = lane in ui.on,
                onClick = { actions.onToggle(lane) },
                label = { Text(stringResource(laneName(lane))) },
                leadingIcon = { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(laneColour(lane))) },
                modifier = Modifier.testTag("timeline-chip-${lane.id}"),
            )
        }
    }
}

/**
 * The river: rows of marks (one per lane switched on, up to three, else one), the lens line, and the ruler. On Home
 * ([full] false) one finger scrubs and that's all; full screen adds the ruler pan and two-finger pinch and pan.
 */
@Composable
private fun River(ui: TimelineUi, actions: TimelineActions, words: YearWords, full: Boolean) {
    val rows = if (ui.on.size in 1..3) Lane.entries.filter { it in ui.on } else listOf(null)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val colours = MaterialTheme.colorScheme
    val laneColours = Lane.entries.associateWith { laneColour(it) }
    val other = colours.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = colours.onSurfaceVariant)
    var scrubX by remember { mutableStateOf<Float?>(null) }
    val current by rememberUpdatedState(ui)
    val rowHeight = when {
        full && rows.size == 1 -> FullSingleRowHeight
        full -> FullRowHeight
        rows.size == 1 -> SingleRowHeight
        else -> RowHeight
    }.let { natural ->
        // On a phone turned sideways the river gives up height, so the ruler (and on Home the lens card) still shows.
        val share = if (full) FULL_RIVER_SHARE else HOME_RIVER_SHARE
        minOf(natural, (LocalConfiguration.current.screenHeightDp.dp * share - RulerHeight) / rows.size)
    }
    val height = rowHeight * rows.size + RulerHeight
    // Dots spread over more tracks as a row gets taller, about one per 18 dp.
    val tracks = (rowHeight.value / 18).toInt().coerceIn(3, 16)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val (from, to) = ui.window.years()
        Canvas(
            Modifier.fillMaxWidth().height(height).testTag("timeline-river")
                .semantics { contentDescription = words.range(from, to) }
                .pointerInput(widthPx, full) {
                    val slop = viewConfiguration.touchSlop
                    val hold = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pressedAt = System.currentTimeMillis()
                        // Home: a drag scrubs the lens. Full screen: a drag pans, a press-and-hold then drag scrubs, two
                        // fingers pinch. A tap selects the nearest mark either way; a vertical drag is the page's scroll.
                        var mode = Mode.UNDECIDED
                        var total = Offset.Zero
                        while (true) {
                            val ev = if (full && mode == Mode.UNDECIDED) {
                                val left = hold - (System.currentTimeMillis() - pressedAt)
                                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
                            } else awaitPointerEvent()
                            if (ev == null) {   // held still: scrub with the loupe
                                mode = Mode.SCRUB
                                scrubX = down.position.x
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                when (mode) {
                                    Mode.SCRUB -> scrubX?.let { actions.onLens(current.window.year(it, widthPx)) }
                                    // A tap picks the dot or bar under the finger, in its row (Neal, 2026-11-08: taps
                                    // landed in the right year but the wrong row); empty space falls back to the year.
                                    Mode.UNDECIDED -> hit(current, rows, rowHeight.toPx(), tracks, widthPx, down.position, 24.dp.toPx())
                                        ?.let(actions.onSelect) ?: actions.onLens(current.window.year(down.position.x, widthPx))
                                    else -> Unit
                                }
                                scrubX = null
                                break
                            }
                            if (pressed.size >= 2 && full) {
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid()
                                actions.onWindow(current.window.zoom(zoom, c.x, widthPx, current.bounds).pan(pan.x, widthPx, current.bounds))
                                ev.changes.forEach { it.consume() }
                                mode = Mode.PINCH
                                scrubX = null
                                continue
                            }
                            val ch = pressed.first()
                            total += ch.position - ch.previousPosition
                            if (mode == Mode.UNDECIDED) {
                                if (abs(total.x) > slop && abs(total.x) > abs(total.y)) mode = if (full) Mode.PAN else Mode.SCRUB
                                else if (abs(total.y) > slop) break
                            }
                            when (mode) {
                                Mode.SCRUB -> { ch.consume(); scrubX = ch.position.x.coerceIn(0f, widthPx) }
                                Mode.PAN -> {
                                    // Read the move before consuming it: a consumed change reports no movement.
                                    val dx = ch.positionChange().x
                                    ch.consume()
                                    actions.onWindow(current.window.pan(dx, widthPx, current.bounds))
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            val w = size.width
            val rowH = rowHeight.toPx()
            val bottom = rowH * rows.size
            // Density from every article's year, not just the dots drawn: where the river has articles even when no dot
            // fits (Neal, 2026-11-08). A low histogram along each row's foot, in a quiet neutral, on a log scale so a
            // quiet century still shows; it was a full-height accent tint that read as muddy stripes behind the dots.
            val bins = 64
            // Low enough to stay under the bottom track's dots.
            val barMax = (rowH * 0.12f).coerceAtMost(14.dp.toPx())
            for ((r, rowLane) in rows.withIndex()) {
                val laneSet = rowLane?.let { setOf(it) } ?: ui.on
                val counts = IntArray(bins) { i ->
                    val a = kotlin.math.ceil(ui.window.year(w * i / bins, w)).toInt()
                    val b = kotlin.math.ceil(ui.window.year(w * (i + 1) / bins, w)).toInt() - 1
                    ui.density.count(a, b, laneSet)
                }
                val peak = kotlin.math.ln(1.0 + (counts.maxOrNull() ?: 0)).coerceAtLeast(1.0)
                val foot = rowH * (r + 1)
                val gap = 1.dp.toPx()
                counts.forEachIndexed { i, n ->
                    if (n > 0) {
                        val h = (barMax * (0.15 + 0.85 * kotlin.math.ln(1.0 + n) / peak)).toFloat()
                        drawRect(colours.onSurfaceVariant.copy(alpha = 0.16f), Offset(w * i / bins, foot - h), Size(w / bins - gap, h))
                    }
                }
            }
            // Marks: lifetimes as thin bars, spans as bars, events as dots; the better known drawn last, on top.
            // While searching, matches draw bright and on top; everything else dims (spec §5.2, search).
            val searching = ui.query.isNotBlank()
            val matchIds = ui.matches.mapTo(HashSet()) { it.qid }
            for (m in ui.visible().sortedBy { if (searching && it.qid in matchIds) Int.MAX_VALUE else it.rank }) {
                val row = rowOf(m, rows) ?: continue
                val lane = rows[row] ?: Lane.entries.firstOrNull { it in m.lanes }
                val dim = searching && m.qid !in matchIds
                val c = (lane?.let { laneColours[it] } ?: other).let { if (dim) it.copy(alpha = 0.18f) else it }
                val y = rowH * row + trackY(m.qid, rowH, tracks)
                val x0 = ui.window.x(m.y0.toDouble(), w)
                when (m.kind) {
                    TimeKind.POINT -> drawCircle(c, (if (searching && !dim) 4.5f else 3f).dp.toPx(), Offset(x0, y))
                    else -> {
                        val x1 = ui.window.x(m.end(ui.now).toDouble(), w).coerceAtLeast(x0 + 2.dp.toPx())
                        val h = if (m.kind == TimeKind.SPAN) 5.dp.toPx() else 2.dp.toPx()
                        drawRoundRect(if (dim) c else c.copy(alpha = 0.85f), Offset(x0, y - h / 2), Size(x1 - x0, h), CornerRadius(h / 2))
                    }
                }
            }
            // The lens: a line at the selected mark (or under the finger while scrubbing).
            val lensX = scrubX ?: ui.selected?.let { ui.window.x(it.y0.toDouble(), w) }
            if (lensX != null) {
                drawLine(colours.primary, Offset(lensX, 0f), Offset(lensX, bottom), strokeWidth = 2.dp.toPx())
                ui.selected?.takeIf { scrubX == null }?.let { s ->
                    val row = rows.indexOfFirst { it == null || it in s.lanes }.coerceAtLeast(0)
                    drawCircle(colours.primary, 6.dp.toPx(), Offset(lensX, rowH * row + trackY(s.qid, rowH, tracks)), style = Stroke(2.dp.toPx()))
                }
            }
            // Row lines and the ruler.
            for (r in 1 until rows.size) drawLine(colours.outlineVariant, Offset(0f, rowH * r), Offset(w, rowH * r), strokeWidth = 1f)
            drawLine(colours.outline, Offset(0f, bottom), Offset(w, bottom), strokeWidth = 1.dp.toPx())
            // The latest year the phone has an article for, at the right edge, in the accent (Neal, 2026-11-08).
            // Only when the view reaches it; panned into the past, the ruler's own ticks carry on to the edge.
            var latestLeft = Float.POSITIVE_INFINITY
            if (ui.window.end >= ui.bounds.end - 1e-6) {
                val latest = measurer.measure(words.year(ui.latest.toLong()), labelStyle.copy(color = colours.primary))
                latestLeft = w - latest.size.width
                drawText(latest, topLeft = Offset(latestLeft, bottom + 5.dp.toPx()))
            }
            // Ticks left to right; a label that would touch the one before it, or the latest year, is skipped (the tick stays).
            var lastRight = Float.NEGATIVE_INFINITY
            for (t in TimeScale.ticks(ui.window, w, TickGap.toPx())) {
                val x = ui.window.x(t.toDouble(), w)
                drawLine(colours.outline, Offset(x, bottom), Offset(x, bottom + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
                val layout = measurer.measure(words.tick(t), labelStyle)
                val lx = (x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width)
                if (lx < lastRight + 8.dp.toPx() || lx + layout.size.width > latestLeft - 8.dp.toPx()) continue
                drawText(layout, topLeft = Offset(lx, bottom + 5.dp.toPx()))
                lastRight = lx + layout.size.width
            }
        }
        // matchParentSize: the loupe floats over the section without taking layout space, so nothing below jumps.
        scrubX?.let { x -> Box(Modifier.matchParentSize()) { Loupe(ui, x, widthPx, words, inside = full) } }
    }
}

/** While scrubbing: the marks nearest the finger, named above the river on Home, inside its top on the taller full screen (spec §3.1, the loupe). */
@Composable
private fun Loupe(ui: TimelineUi, x: Float, widthPx: Float, words: YearWords, inside: Boolean) {
    val near = ui.marks.sortedBy { abs(ui.window.x(it.y0.toDouble(), widthPx) - x) }.take(LOUPE_MARKS).sortedWith(TimelineModel.ORDER)
    if (near.isEmpty()) return
    val density = LocalDensity.current
    val boxW = with(density) { 220.dp.toPx() }
    val left = (x - boxW / 2).coerceIn(0f, (widthPx - boxW).coerceAtLeast(0f))
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.zIndex(2f).offset { IntOffset(left.roundToInt(), if (inside) with(density) { 8.dp.roundToPx() } else -with(density) { (24 * near.size + 16).dp.roundToPx() }) }
            .widthIn(max = 220.dp).testTag("timeline-loupe"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            for (m in near) {
                Text(
                    "${words.of(m)} · ${m.title}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The selected mark in one row (Neal, 2026-11-08: the step buttons sat on a row of their own): ‹, the mark's picture,
 * dates and title as one card a tap opens, and ›. Then, full screen, the marks nearby.
 */
@Composable
private fun LensCard(mark: TimeMark, ui: TimelineUi, actions: TimelineActions, words: YearWords, onOpen: (TimeMark) -> Unit, nearby: Boolean) {
    val picture = rememberRailCover(ui.picture, 56.dp)
    val openLabel = stringResource(R.string.home_wander_open)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("timeline-card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onStep(-1) }, modifier = Modifier.testTag("timeline-prev")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.home_timeline_earlier))
            }
            Surface(
                onClick = { onOpen(mark) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // TalkBack reads the card's year and title, then "double-tap to Open" (a description repeated the title).
                modifier = Modifier.weight(1f).semantics { onClick(label = openLabel) { onOpen(mark); true } }.testTag("timeline-open"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                        else Text(
                            initials(mark.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clearAndSetSemantics {},   // a stand-in for a picture, not read aloud
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(words.of(mark), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("timeline-year"))
                        Text(
                            mark.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("timeline-title"),
                        )
                    }
                }
            }
            IconButton(onClick = { actions.onStep(1) }, modifier = Modifier.testTag("timeline-next")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.home_timeline_later))
            }
        }
        if (nearby && ui.nearby.isNotEmpty()) {
            Text(
                stringResource(R.string.home_timeline_nearby),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
            for (n in ui.nearby) {
                Text(
                    "${words.of(n)} · ${n.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { actions.onSelect(n) }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("timeline-nearby-${n.qid}"),
                )
            }
        }
    }
}


/** A mark's height within its row: one of [TRACKS] tracks by its id, so neighbours spread instead of stacking. */
internal fun trackY(qid: Long, rowH: Float, tracks: Int = TRACKS): Float = rowH * (0.08f + 0.84f * ((qid % tracks).toInt() + 0.5f) / tracks)

private enum class Mode { UNDECIDED, SCRUB, PAN, PINCH }

/** What the river draws: the window's marks plus any search matches. */
internal fun TimelineUi.visible(): List<TimeMark> = if (matches.isEmpty()) marks else (marks + matches).distinctBy { it.qid }

/** The row a mark is drawn in: the first switched-on lane it belongs to, or the single row; null if it's in none shown. */
private fun rowOf(m: TimeMark, rows: List<Lane?>): Int? = rows.indexOfFirst { it == null || it in m.lanes }.takeIf { it >= 0 }

/**
 * The mark drawn nearest [at], within [reach] pixels: dots by distance to their centre, bars by distance to the bar.
 * Uses the same rows and tracks as the drawing, so what you tap is what you get.
 */
internal fun hit(ui: TimelineUi, rows: List<Lane?>, rowH: Float, tracks: Int, width: Float, at: Offset, reach: Float): TimeMark? {
    var best: TimeMark? = null
    var bestD = reach
    for (m in ui.visible()) {
        val row = rowOf(m, rows) ?: continue
        val y = rowH * row + trackY(m.qid, rowH, tracks)
        val x0 = ui.window.x(m.y0.toDouble(), width)
        val x1 = if (m.kind == TimeKind.POINT) x0 else maxOf(x0, ui.window.x(m.end(ui.now).toDouble(), width))
        val dx = when {
            at.x < x0 -> x0 - at.x
            at.x > x1 -> at.x - x1
            else -> 0f
        }
        val d = kotlin.math.hypot(dx, at.y - y)
        if (d < bestD || (d == bestD && best != null && m.rank > best.rank)) {
            best = m
            bestD = d
        }
    }
    return best
}

private fun laneName(lane: Lane): Int = when (lane) {
    Lane.PHILOSOPHY_RELIGION -> R.string.home_lane_philosophy_religion
    Lane.CIVILISATIONS -> R.string.home_lane_civilisations
    Lane.LANGUAGES -> R.string.home_lane_languages
    Lane.FILM_MUSIC -> R.string.home_lane_film_music
    Lane.ART_LITERATURE -> R.string.home_lane_art_literature
    Lane.SCIENCE -> R.string.home_lane_science
    Lane.WARS -> R.string.home_lane_wars
    Lane.EVENTS_POLITICS -> R.string.home_lane_events_politics
    Lane.PEOPLE -> R.string.home_lane_people
}

/** A colour per lane, deeper on Field and lighter on Night so marks keep their contrast in both (spec §7). */
@Composable
internal fun laneColour(lane: Lane): Color {
    val night = LocalGrangerExtras.current.night
    val (day, dark) = when (lane) {
        Lane.PHILOSOPHY_RELIGION -> 0xFF7B4FA0 to 0xFFC7A6E6
        Lane.CIVILISATIONS -> 0xFFA0522D to 0xFFE3A57A
        Lane.LANGUAGES -> 0xFF2E7D6B to 0xFF7FD1BC
        Lane.FILM_MUSIC -> 0xFFB03A6A to 0xFFF09BBE
        Lane.ART_LITERATURE -> 0xFF8A6D1E to 0xFFE2C46E
        Lane.SCIENCE -> 0xFF2F5FA8 to 0xFF8DB4F0
        Lane.WARS -> 0xFF9E2B25 to 0xFFF08A80
        Lane.EVENTS_POLITICS -> 0xFF4F6A2A to 0xFFB2D27A
        Lane.PEOPLE -> 0xFF6B5B4B to 0xFFCDBBA6
    }
    return Color(if (night) dark else day)
}

/** Year wording for marks, the ruler and TalkBack, in the app's language. */
internal class YearWords(
    private val bc: String,
    private val circa: String,
    private val millionAgo: String,
    private val billionAgo: String,
    private val rangeText: String,
    private val living: String,
) {
    private fun num(n: Long) = String.format(Locale.getDefault(), "%,d", n)

    /** One year: 1066, 500 BC, 10,000 BC, 65 million years ago. Years are astronomical (500 BC = -499). */
    fun year(y: Long): String = when {
        y > 0 -> y.toString()
        y > -9_999 -> bc.format((1 - y).toString())     // 1500 BC, written like 1500
        y > -999_999 -> bc.format(num(1 - y))             // 10,000 BC
        y > -1_000_000_000 -> millionAgo.format(trim((1 - y) / 1e6))
        else -> billionAgo.format(trim((1 - y) / 1e9))
    }

    fun tick(y: Long): String = year(y)

    /** A mark's dates: a point's year, a span or lifetime's two ends, "c." for anything coarser than a year. */
    fun of(m: TimeMark): String {
        val a = year(rounded(m.y0, m.prec))
        val text = when {
            m.kind == TimeKind.POINT -> a
            m.y1 != null -> "$a – ${year(rounded(m.y1!!, m.prec))}"
            m.kind == TimeKind.LIFE -> living.format(a)
            else -> a
        }
        return if (m.prec < 9) circa.format(text) else text
    }

    /**
     * A year rounded to what its precision supports, as people write it: Wikidata stores "the 4th century" as 301, which
     * reads as c. 300; 1450s as c. 1450; 1500 BC (astronomical -1499) stays 1500 BC.
     */
    private fun rounded(y: Int, prec: Int): Long {
        val unit = when (prec) { 8 -> 10; 7 -> 100; 6 -> 1000; else -> return y.toLong() }
        val written = if (y > 0) y.toLong() else 1L - y
        val r = written / unit * unit
        val w = if (r == 0L) written else r
        return if (y > 0) w else 1 - w
    }

    fun range(from: Int, to: Int): String = rangeText.format(year(from.toLong()), year(to.toLong()))

    /** 66 and 230 stay whole; 13.8 and 2.5 keep their decimal. */
    private fun trim(v: Double): String {
        val tenths = kotlin.math.round(v * 10) / 10
        return if (v >= 100 || tenths == kotlin.math.floor(tenths)) num(v.roundToLong()) else String.format(Locale.getDefault(), "%.1f", tenths)
    }

    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
}

@Composable
internal fun yearWords(): YearWords = YearWords(
    bc = stringResource(R.string.home_timeline_bc),
    circa = stringResource(R.string.home_timeline_circa),
    millionAgo = stringResource(R.string.home_timeline_million_ago),
    billionAgo = stringResource(R.string.home_timeline_billion_ago),
    rangeText = stringResource(R.string.home_timeline_range),
    living = stringResource(R.string.home_timeline_living),
)
