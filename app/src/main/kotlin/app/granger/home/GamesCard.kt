package app.granger.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.core.library.TimeKind
import app.granger.core.library.TimeMark
import app.granger.shell.rememberRailCover
import app.granger.theme.Spectral

/**
 * Home's Play section (Plan H5c, Neal 2026-11-08): one round, Which came first? and Guess the year in turn, with
 * More rounds › to the Games screen. Taps and the slider only, so nothing here fights Home's scroll.
 */
@Composable
fun PlaySection(ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, onMore: () -> Unit) {
    LaunchedEffect(Unit) { actions.onShown() }
    SectionHeading(stringResource(R.string.home_play), action = stringResource(R.string.home_play_more), onAction = onMore, actionTag = "play-more")
    GameBody(ui, actions, onOpen, Modifier.testTag("play"), showName = true)
}

/** The Games screen: both games, chosen by tabs, and the count of rounds played, kept on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { actions.onShown() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.games_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.home_timeline_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("games-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                for ((i, kind) in GameKind.entries.withIndex()) {
                    SegmentedButton(
                        selected = ui.kind == kind,
                        onClick = { actions.onChoose(kind) },
                        shape = SegmentedButtonDefaults.itemShape(i, GameKind.entries.size),
                        modifier = Modifier.testTag("games-tab-${kind.name.lowercase()}"),
                    ) { Text(stringResource(kind.label()), maxLines = 1) }
                }
            }
            GameBody(ui, actions, onOpen, Modifier, showName = false)
            if (ui.played > 0) {
                Text(
                    pluralStringResource(R.plurals.games_played, ui.played, ui.played),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("games-played"),
                )
            }
        }
    }
}

private fun GameKind.label(): Int = when (this) {
    GameKind.ORDER -> R.string.game_order
    GameKind.GUESS -> R.string.game_guess
}

@Composable
private fun GameBody(ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, modifier: Modifier, showName: Boolean) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val round = ui.round
        if (round == null) {
            Text(
                stringResource(if (ui.loading) R.string.game_loading else R.string.game_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        if (showName) {
            Text(
                stringResource(if (round is Round.Order) GameKind.ORDER.label() else GameKind.GUESS.label()),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral),
                modifier = Modifier.testTag("play-name"),
            )
        }
        val words = yearWords()
        when (round) {
            is Round.Order -> OrderRound(round, ui, actions, onOpen, words)
            is Round.Guess -> GuessRound(round, ui, actions, onOpen, words)
        }
        if (round.revealed) {
            FilledTonalButton(onClick = actions.onNext, modifier = Modifier.align(Alignment.End).testTag("game-next")) {
                Text(stringResource(R.string.game_next))
            }
        }
    }
}

@Composable
private fun OrderRound(round: Round.Order, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    if (!round.revealed) {
        Text(
            stringResource(if (round.marks.size == 2) R.string.game_order_prompt_two else R.string.game_order_prompt_three),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (m in round.marks) {
            val at = round.picked.indexOf(m.qid)
            val action = if (at >= 0) null else stringResource(R.string.game_choose_as, round.picked.size + 1)
            val state = if (at >= 0) stringResource(R.string.game_chosen, at + 1) else null
            MarkCard(
                m, ui.pictures[m.qid], year = null, badge = if (at >= 0) "${at + 1}" else null,
                onTap = { actions.onPick(m.qid) }, clickLabel = action, state = state, tag = "game-card-${m.qid}",
            )
        }
        return
    }
    Verdict(
        stringResource(if (round.right) R.string.game_order_right else R.string.game_order_wrong),
        pluralStringResource(R.plurals.game_years_apart, round.gap, round.gap),
        good = round.right,
    )
    RevealStrip(round.answer.map { it.y0 to words.year(it.y0.toLong()) }, guess = null)
    val open = stringResource(R.string.home_wander_open)
    for ((i, m) in round.answer.withIndex()) {
        val placed = round.picked.getOrNull(i) == m.qid
        MarkCard(
            m, ui.pictures[m.qid], year = words.year(m.y0.toLong()), badge = null, mark = placed,
            onTap = { onOpen(m) }, clickLabel = open,
            state = stringResource(if (placed) R.string.game_right_place else R.string.game_wrong_place), tag = "game-answer-${m.qid}",
        )
    }
}

@Composable
private fun GuessRound(round: Round.Guess, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    val m = round.mark
    val open = stringResource(R.string.home_wander_open)
    if (!round.revealed) {
        Text(
            stringResource(
                when (m.kind) {
                    TimeKind.POINT -> R.string.game_guess_prompt_point
                    TimeKind.LIFE -> R.string.game_guess_prompt_life
                    TimeKind.SPAN -> R.string.game_guess_prompt_span
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Before the guess the card doesn't open: the article's first line would give the year away.
        MarkCard(m, ui.pictures[m.qid], year = null, badge = null, onTap = null, clickLabel = null, state = null, tag = "game-guess-card")
        val shown = words.year(round.guess.toLong())
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                shown,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Spectral),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("game-guess-year"),
            )
        }
        val w = TimeScale.history(ui.latest)
        val label = stringResource(R.string.game_guess_slider)
        Slider(
            value = ((TimeScale.toAxis(round.guess.toDouble()) - w.start) / w.span).toFloat().coerceIn(0f, 1f),
            onValueChange = { f -> actions.onGuess(TimeScale.toYear(w.start + f * w.span).toInt()) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = shown }.testTag("game-slider"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Nudge("−10", R.string.game_earlier_ten) { actions.onNudge(-10) }
            Nudge("−1", R.string.game_earlier_one) { actions.onNudge(-1) }
            Box(Modifier.weight(1f))
            Nudge("+1", R.string.game_later_one) { actions.onNudge(1) }
            Nudge("+10", R.string.game_later_ten) { actions.onNudge(10) }
        }
        FilledTonalButton(onClick = actions.onSubmit, modifier = Modifier.fillMaxWidth().testTag("game-submit")) {
            Text(stringResource(R.string.game_guess_submit))
        }
        return
    }
    val near = closeness(round.off, m.y0, ui.now)
    Verdict(
        stringResource(
            when (near) {
                Closeness.SPOT_ON -> R.string.game_spot_on
                Closeness.VERY_CLOSE -> R.string.game_very_close
                Closeness.CLOSE -> R.string.game_close
                Closeness.OFF -> R.string.game_off
            },
        ),
        if (round.off == 0) stringResource(R.string.game_it_was, words.year(m.y0.toLong()))
        else stringResource(R.string.game_it_was, words.year(m.y0.toLong())) + " · " + pluralStringResource(R.plurals.game_years_off, round.off, round.off),
        good = near != Closeness.OFF,
    )
    RevealStrip(listOf(m.y0 to words.year(m.y0.toLong())), guess = round.guess to stringResource(R.string.game_guess_year, words.year(round.guess.toLong())))
    MarkCard(m, ui.pictures[m.qid], year = words.year(m.y0.toLong()), badge = null, onTap = { onOpen(m) }, clickLabel = open, state = null, tag = "game-answer-${m.qid}")
}

@Composable
private fun Nudge(text: String, description: Int, onClick: () -> Unit) {
    val cd = stringResource(description)
    OutlinedButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = cd }) { Text(text) }
}

@Composable
private fun Verdict(title: String, detail: String, good: Boolean) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("game-verdict")) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Spectral),
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One article as a card: picture, title, and what's being dated (born, began). [badge] is the order it was tapped in;
 * [mark] true/false shows whether it ended up in the right place.
 */
@Composable
private fun MarkCard(
    m: TimeMark, picturePath: String?, year: String?, badge: String?, onTap: (() -> Unit)?, clickLabel: String?, state: String?,
    tag: String, mark: Boolean? = null,
) {
    val picture = rememberRailCover(picturePath, 48.dp)
    val qualifier = when (m.kind) {
        TimeKind.LIFE -> stringResource(R.string.game_born)
        TimeKind.SPAN -> stringResource(R.string.game_began)
        TimeKind.POINT -> null
    }
    val selected = badge != null
    val colour = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(12.dp)
    val modifier = Modifier.fillMaxWidth().semantics {
        if (state != null) stateDescription = state
        if (onTap != null && clickLabel != null) onClick(label = clickLabel) { onTap(); true }
    }.testTag(tag)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
                else Text(initials(m.title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
            }
            Column(Modifier.weight(1f)) {
                if (year != null) Text(year, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(m.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (qualifier != null) Text(qualifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                badge != null -> Box(
                    Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) { Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary) }
                mark != null -> Icon(
                    if (mark) Icons.Filled.Check else Icons.Filled.Close, null,
                    tint = if (mark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
    // A card that can't be tapped yet (Guess the year's, before the guess) is plain, not a disabled button.
    if (onTap != null) Surface(onClick = onTap, shape = shape, color = colour, modifier = modifier, content = content)
    else Surface(shape = shape, color = colour, modifier = modifier, content = content)
}

/**
 * The answers dropped onto the timeline's own axis (spec §3.3: "drops them onto the time axis with the real gap"):
 * filled dots for the real years, labelled; a hollow one for a guess. Fitted to the years shown, with room either side.
 */
@Composable
private fun RevealStrip(years: List<Pair<Int, String>>, guess: Pair<Int, String>?) {
    val all = years.map { it.first } + listOfNotNull(guess?.first)
    val lo = TimeScale.toAxis(all.min().toDouble())
    val hi = TimeScale.toAxis(all.max().toDouble())
    val pad = maxOf((hi - lo) * 0.15, TimeScale.toAxis(all.max().toDouble()) - TimeScale.toAxis(all.max() - 5.0))
    val window = TimeScale.Window(lo - pad, hi + pad)
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.tertiary
    val text = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics {}.testTag("game-strip")) {
        val y = size.height / 2
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        // Labels alternate above and below the line so neighbours don't overlap.
        val labelled = years.map { Triple(it.first, it.second, false) } + listOfNotNull(guess?.let { Triple(it.first, it.second, true) })
        for ((i, item) in labelled.sortedBy { it.first }.withIndex()) {
            val (yr, label, isGuess) = item
            val x = window.x(yr.toDouble(), size.width)
            if (isGuess) drawCircle(ring, 7.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
            else drawCircle(dot, 6.dp.toPx(), Offset(x, y))
            val laid = measurer.measure(label, text)
            val lx = (x - laid.size.width / 2).coerceIn(0f, size.width - laid.size.width)
            val ly = if (i % 2 == 0) y - 12.dp.toPx() - laid.size.height else y + 12.dp.toPx()
            drawText(laid, topLeft = Offset(lx, ly))
        }
    }
}
