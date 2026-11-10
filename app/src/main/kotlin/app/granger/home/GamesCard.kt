package app.granger.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
 * Home's Play section (Plan H5c; Plan HY, Neal 2026-11-10): one round of a game picked at random, with More games › to
 * the Games screen. Taps and the slider only, so nothing here fights Home's scroll.
 */
@Composable
fun PlaySection(ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, onMore: () -> Unit) {
    LaunchedEffect(Unit) { actions.onShown() }
    SectionHeading(stringResource(R.string.home_play), action = stringResource(R.string.home_play_more), onAction = onMore, actionTag = "play-more")
    GameBody(ui, actions, onOpen, Modifier.testTag("play"), showName = true)
}

/**
 * The Games screen: Continue at the top, the game Home was showing played on as a full run (its unanswered round first),
 * then every game, each with its best run. A run is [GameModel.RUN] rounds of one game with a score at the end.
 * [carry] is Home's round; [onContinue] starts the run from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    ui: GameUi,
    actions: GameActions,
    onOpen: (TimeMark) -> Unit,
    onBack: () -> Unit,
    carry: GameUi? = null,
    onContinue: () -> Unit = {},
) {
    LaunchedEffect(Unit) { actions.onShown() }
    val run = ui.run
    BackHandler(enabled = run != null) { actions.onEndRun() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (run != null) stringResource(run.kind.label()) else stringResource(R.string.games_title)) },
                navigationIcon = {
                    if (run != null) {
                        IconButton(onClick = actions.onEndRun, modifier = Modifier.testTag("games-to-list")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.games_all))
                        }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.home_timeline_back)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("games-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (run == null) GamesList(ui, actions, carry, onContinue) else RunView(ui, run, actions, onOpen)
        }
    }
}

@Composable
private fun GamesList(ui: GameUi, actions: GameActions, carry: GameUi?, onContinue: () -> Unit) {
    val round = carry?.round
    if (round != null) {
        Heading(stringResource(R.string.games_continue))
        Surface(
            onClick = onContinue,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("games-continue"),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameGlyph(round.kind, MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(stringResource(round.kind.label()), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Spectral), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in round.marks.take(4)) Thumb(m, carry.pictures[m.qid], 40)
                }
                Text(
                    if (!round.revealed) stringResource(R.string.games_continue_carry, GameModel.RUN)
                    else stringResource(R.string.games_continue_fresh, GameModel.RUN),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onContinue, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.games_continue_button)) }
            }
        }
    }
    Heading(stringResource(R.string.games_all))
    for (kind in GameKind.entries) {
        val best = ui.best[kind]
        Surface(
            onClick = { actions.onStart(kind) },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("games-start-${kind.name.lowercase()}"),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameGlyph(kind, MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(kind.label()), style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral))
                    Text(stringResource(kind.about()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (best != null) {
                        Text(stringResource(R.string.games_best, best, GameModel.RUN), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (ui.played > 0) {
        Text(
            pluralStringResource(R.plurals.games_played, ui.played, ui.played),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("games-played"),
        )
    }
}

/** A run: where it's got to, the round, and at the end the score with Play again. */
@Composable
private fun RunView(ui: GameUi, run: GameRun, actions: GameActions, onOpen: (TimeMark) -> Unit) {
    if (run.finished) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp).semantics(mergeDescendants = true) {}.testTag("games-score"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameGlyph(run.kind, MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.games_score, run.right, run.size), style = MaterialTheme.typography.displaySmall.copy(fontFamily = Spectral))
            Text(
                if (run.newBest) stringResource(R.string.games_new_best) else stringResource(R.string.games_best, run.best, run.size),
                style = MaterialTheme.typography.titleMedium,
                color = if (run.newBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            OutlinedButton(onClick = actions.onEndRun, modifier = Modifier.testTag("games-all")) { Text(stringResource(R.string.games_all)) }
            Button(onClick = { actions.onStart(run.kind) }, modifier = Modifier.testTag("games-again")) { Text(stringResource(R.string.games_again)) }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("games-progress"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.games_round_of, run.number, run.size) + " · " + pluralStringResource(R.plurals.games_right, run.right, run.right),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val done = run.number - if (ui.round?.revealed == true) 0 else 1
        LinearProgressIndicator(progress = { done.toFloat() / run.size }, modifier = Modifier.fillMaxWidth())
    }
    GameBody(ui, actions, onOpen, Modifier, showName = false, nextLabel = if (run.last) R.string.games_see_score else R.string.game_next)
}

internal fun GameKind.label(): Int = when (this) {
    GameKind.ORDER -> R.string.game_order
    GameKind.GUESS -> R.string.game_guess
    GameKind.BEFORE_AFTER -> R.string.game_before_after
    GameKind.OVERLAP -> R.string.game_overlap
    GameKind.ODD_ONE_OUT -> R.string.game_odd
    GameKind.CENTURY -> R.string.game_century_name
}

private fun GameKind.about(): Int = when (this) {
    GameKind.ORDER -> R.string.game_order_about
    GameKind.GUESS -> R.string.game_guess_about
    GameKind.BEFORE_AFTER -> R.string.game_before_after_about
    GameKind.OVERLAP -> R.string.game_overlap_about
    GameKind.ODD_ONE_OUT -> R.string.game_odd_about
    GameKind.CENTURY -> R.string.game_century_about
}

@Composable
private fun GameBody(ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, modifier: Modifier, showName: Boolean, nextLabel: Int = R.string.game_next) {
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
                stringResource(round.kind.label()),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = Spectral),
                modifier = Modifier.semantics { heading() }.testTag("play-name"),
            )
        }
        val words = yearWords()
        when (round) {
            is Round.Order -> OrderRound(round, ui, actions, onOpen, words)
            is Round.Guess -> GuessRound(round, ui, actions, onOpen, words)
            is Round.BeforeAfter -> BeforeAfterRound(round, ui, actions, onOpen, words)
            is Round.Overlap -> OverlapRound(round, ui, actions, onOpen, words)
            is Round.OddOneOut -> OddRound(round, ui, actions, onOpen, words)
            is Round.Century -> CenturyRound(round, ui, actions, onOpen, words)
        }
        if (round.revealed) {
            FilledTonalButton(onClick = actions.onNext, modifier = Modifier.align(Alignment.End).testTag("game-next")) {
                Text(stringResource(nextLabel))
            }
        }
    }
}

@Composable
private fun Prompt(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrderRound(round: Round.Order, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    if (!round.revealed) {
        Prompt(stringResource(if (round.marks.size == 2) R.string.game_order_prompt_two else R.string.game_order_prompt_three))
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
        Prompt(
            stringResource(
                when (m.kind) {
                    TimeKind.POINT -> R.string.game_guess_prompt_point
                    TimeKind.LIFE -> R.string.game_guess_prompt_life
                    TimeKind.SPAN -> R.string.game_guess_prompt_span
                },
            ),
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

/** Before or after?: the anchor shows its year; the other card is placed against it with two buttons. */
@Composable
private fun BeforeAfterRound(round: Round.BeforeAfter, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    val open = stringResource(R.string.home_wander_open)
    val anchorYear = words.year(round.anchor.y0.toLong())
    if (!round.revealed) {
        Prompt(stringResource(R.string.game_before_after_prompt, round.anchor.title, anchorYear))
        MarkCard(round.mark, ui.pictures[round.mark.qid], year = null, badge = null, onTap = null, clickLabel = null, state = null, tag = "game-question-card")
        MarkCard(round.anchor, ui.pictures[round.anchor.qid], year = anchorYear, badge = null, onTap = null, clickLabel = null, state = null, tag = "game-anchor-card", quiet = true)
        TwoChoices(R.string.game_before, R.string.game_after, "game-before", "game-after", onFirst = { actions.onSay(true) }, onSecond = { actions.onSay(false) })
        return
    }
    Verdict(
        stringResource(if (round.right) R.string.game_order_right else R.string.game_wrong),
        pluralStringResource(if (round.before) R.plurals.game_years_before else R.plurals.game_years_after, round.gap, round.gap),
        good = round.right,
    )
    RevealStrip(listOf(round.anchor, round.mark).map { it.y0 to words.year(it.y0.toLong()) }, guess = null)
    for (m in listOf(round.anchor, round.mark).sortedBy { it.y0 }) {
        MarkCard(m, ui.pictures[m.qid], year = words.year(m.y0.toLong()), badge = null, onTap = { onOpen(m) }, clickLabel = open, state = null, tag = "game-answer-${m.qid}")
    }
}

/** Alive at the same time?: two people, Yes or No, then their lifetimes drawn as bars on one axis. */
@Composable
private fun OverlapRound(round: Round.Overlap, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    val open = stringResource(R.string.home_wander_open)
    if (!round.revealed) {
        Prompt(stringResource(R.string.game_overlap_prompt))
        for (m in listOf(round.a, round.b)) {
            MarkCard(m, ui.pictures[m.qid], year = null, badge = null, onTap = null, clickLabel = null, state = null, tag = "game-card-${m.qid}", plain = true)
        }
        TwoChoices(R.string.game_yes, R.string.game_no, "game-yes", "game-no", onFirst = { actions.onSay(true) }, onSecond = { actions.onSay(false) })
        return
    }
    Verdict(
        stringResource(if (round.right) R.string.game_order_right else R.string.game_wrong),
        if (round.overlaps) pluralStringResource(R.plurals.game_overlap_shared, round.years, round.years)
        else pluralStringResource(R.plurals.game_years_apart, round.years, round.years),
        good = round.right,
    )
    LifeStrip(listOf(round.a, round.b).sortedBy { it.y0 }, ui.now)
    for (m in listOf(round.a, round.b).sortedBy { it.y0 }) {
        MarkCard(m, ui.pictures[m.qid], year = words.of(m), badge = null, onTap = { onOpen(m) }, clickLabel = open, state = null, tag = "game-answer-${m.qid}", plain = true)
    }
}

/** Odd one out: four cards, one tap; the reveal puts all four on the axis and marks the odd one. */
@Composable
private fun OddRound(round: Round.OddOneOut, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    val open = stringResource(R.string.home_wander_open)
    if (!round.revealed) {
        Prompt(stringResource(R.string.game_odd_prompt))
        val choose = stringResource(R.string.game_odd_choose)
        for (m in round.marks) {
            MarkCard(m, ui.pictures[m.qid], year = null, badge = null, onTap = { actions.onPick(m.qid) }, clickLabel = choose, state = null, tag = "game-card-${m.qid}")
        }
        return
    }
    val odd = round.marks.first { it.qid == round.odd }
    Verdict(
        stringResource(if (round.right) R.string.game_order_right else R.string.game_wrong),
        stringResource(R.string.game_odd_was, odd.title, words.year(odd.y0.toLong())),
        good = round.right,
    )
    RevealStrip(round.marks.map { it.y0 to words.year(it.y0.toLong()) }, guess = null)
    for (m in round.marks.sortedBy { it.y0 }) {
        val mark = when (m.qid) { round.odd -> true; round.picked -> false; else -> null }
        MarkCard(
            m, ui.pictures[m.qid], year = words.year(m.y0.toLong()), badge = null, mark = mark, onTap = { onOpen(m) }, clickLabel = open,
            state = if (m.qid == round.odd) stringResource(R.string.game_odd_state) else null, tag = "game-answer-${m.qid}",
        )
    }
}

/** Which century?: four centuries in a row as buttons, two by two. */
@Composable
private fun CenturyRound(round: Round.Century, ui: GameUi, actions: GameActions, onOpen: (TimeMark) -> Unit, words: YearWords) {
    val m = round.mark
    val names = centuryNames()
    if (!round.revealed) {
        Prompt(
            stringResource(
                when (m.kind) {
                    TimeKind.POINT -> R.string.game_century_prompt_point
                    TimeKind.LIFE -> R.string.game_century_prompt_life
                    TimeKind.SPAN -> R.string.game_century_prompt_span
                },
            ),
        )
        MarkCard(m, ui.pictures[m.qid], year = null, badge = null, onTap = null, clickLabel = null, state = null, tag = "game-guess-card")
        for (pair in round.choices.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in pair) {
                    OutlinedButton(onClick = { actions.onCentury(c) }, modifier = Modifier.weight(1f).testTag("game-century-$c")) {
                        Text(names.of(c), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return
    }
    Verdict(
        stringResource(if (round.right) R.string.game_order_right else R.string.game_wrong),
        stringResource(R.string.game_it_was, words.year(m.y0.toLong())) + " · " + names.of(round.answer),
        good = round.right,
    )
    MarkCard(m, ui.pictures[m.qid], year = words.year(m.y0.toLong()), badge = null, onTap = { onOpen(m) }, clickLabel = stringResource(R.string.home_wander_open), state = null, tag = "game-answer-${m.qid}")
}

/** Century names in the app's language: "19th century", "5th century BC"; "Siglo XIX", "Siglo V a. C.". */
internal class CenturyNames(private val ad: String, private val bc: String, private val roman: Boolean, private val ordinals: List<String>) {
    fun number(n: Int): String = if (roman) roman(n) else when {
        n % 100 in 11..13 -> ordinals[3].format(n)
        n % 10 == 1 -> ordinals[0].format(n)
        n % 10 == 2 -> ordinals[1].format(n)
        n % 10 == 3 -> ordinals[2].format(n)
        else -> ordinals[3].format(n)
    }

    /** [century] as [centuryOf] gives it: 18 is the 19th century, −5 the 5th century BC. */
    fun of(century: Int): String = if (century >= 0) ad.format(number(century + 1)) else bc.format(number(-century))

    private fun roman(n: Int): String {
        val values = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var left = n
        return buildString { for ((v, s) in values) while (left >= v) { append(s); left -= v } }
    }
}

@Composable
internal fun centuryNames() = CenturyNames(
    ad = stringResource(R.string.game_century),
    bc = stringResource(R.string.game_century_bc),
    roman = stringResource(R.string.game_century_numerals) == "roman",
    ordinals = listOf(
        stringResource(R.string.game_ordinal_st), stringResource(R.string.game_ordinal_nd),
        stringResource(R.string.game_ordinal_rd), stringResource(R.string.game_ordinal_th),
    ),
)

@Composable
private fun TwoChoices(first: Int, second: Int, firstTag: String, secondTag: String, onFirst: () -> Unit, onSecond: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onFirst, modifier = Modifier.weight(1f).testTag(firstTag)) { Text(stringResource(first)) }
        FilledTonalButton(onClick = onSecond, modifier = Modifier.weight(1f).testTag(secondTag)) { Text(stringResource(second)) }
    }
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

/** A small square picture of an article, or its initials. */
@Composable
private fun Thumb(m: TimeMark, picturePath: String?, size: Int) {
    val picture = rememberRailCover(picturePath, size.dp)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
        else Text(initials(m.title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * One article as a card: picture, title, and what's being dated (born, began). [badge] is the order it was tapped in;
 * [mark] true/false shows whether it ended up in the right place. [quiet] is a card for reference (Before or after?'s
 * anchor); [plain] leaves out "born" where every card is a person.
 */
@Composable
private fun MarkCard(
    m: TimeMark, picturePath: String?, year: String?, badge: String?, onTap: (() -> Unit)?, clickLabel: String?, state: String?,
    tag: String, mark: Boolean? = null, quiet: Boolean = false, plain: Boolean = false,
) {
    val qualifier = when {
        plain -> null
        m.kind == TimeKind.LIFE -> stringResource(R.string.game_born)
        m.kind == TimeKind.SPAN -> stringResource(R.string.game_began)
        else -> null
    }
    val selected = badge != null
    val colour = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        quiet -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val shape = RoundedCornerShape(12.dp)
    val modifier = Modifier.fillMaxWidth().semantics {
        if (state != null) stateDescription = state
        if (onTap != null && clickLabel != null) onClick(label = clickLabel) { onTap(); true }
    }.testTag(tag)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            Thumb(m, picturePath, if (quiet) 36 else 48)
            Column(Modifier.weight(1f)) {
                if (year != null) Text(year, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    m.title,
                    style = (if (quiet) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium).copy(fontFamily = Spectral),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
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

/** Two lifetimes as bars on one plain axis of years, so the overlap, or the gap, is what the eye sees. */
@Composable
private fun LifeStrip(people: List<TimeMark>, now: Int) {
    val lo = people.minOf { it.y0 }
    val hi = people.maxOf { it.end(now) }
    val pad = maxOf(5, (hi - lo) / 10)
    val from = lo - pad
    val span = (hi + pad - from).toFloat()
    val colours = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val line = MaterialTheme.colorScheme.outlineVariant
    val shared = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Canvas(Modifier.fillMaxWidth().height(56.dp).testTag("game-life-strip").clearAndSetSemantics {}) {
        fun x(year: Int) = (year - from) / span * size.width
        val s = maxOf(people[0].y0, people[1].y0)
        val e = minOf(people[0].end(now), people[1].end(now))
        if (s <= e) drawRect(shared, Offset(x(s), 0f), Size(maxOf(2f, x(e) - x(s)), size.height))
        val bar = 12.dp.toPx()
        for ((i, p) in people.withIndex()) {
            val top = size.height * (if (i == 0) 0.22f else 0.58f) - bar / 2
            drawRoundRect(colours[i], Offset(x(p.y0), top), Size(maxOf(bar, x(p.end(now)) - x(p.y0)), bar), CornerRadius(bar / 2))
        }
        drawLine(line, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}

/** Each game's small drawn sign, in the list and on Continue. */
@Composable
private fun GameGlyph(kind: GameKind, colour: Color) {
    val faint = colour.copy(alpha = 0.35f)
    Canvas(Modifier.size(36.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val stroke = 2.5.dp.toPx()
        val r = 4.dp.toPx()
        when (kind) {
            GameKind.ORDER -> {
                drawLine(faint, Offset(0f, h * 0.8f), Offset(w, h * 0.8f), stroke, StrokeCap.Round)
                for ((i, f) in listOf(0.2f, 0.5f, 0.8f).withIndex()) drawCircle(colour, r, Offset(w * f, h * (0.6f - i * 0.18f)))
            }
            GameKind.GUESS -> {
                drawLine(faint, Offset(0f, h / 2), Offset(w, h / 2), stroke, StrokeCap.Round)
                drawLine(colour, Offset(0f, h / 2), Offset(w * 0.62f, h / 2), stroke, StrokeCap.Round)
                drawCircle(colour, r * 1.6f, Offset(w * 0.62f, h / 2))
            }
            GameKind.BEFORE_AFTER -> {
                drawLine(faint, Offset(0f, h / 2), Offset(w, h / 2), stroke, StrokeCap.Round)
                drawLine(colour, Offset(w / 2, h * 0.2f), Offset(w / 2, h * 0.8f), stroke, StrokeCap.Round)
                drawCircle(colour, r, Offset(w * 0.15f, h / 2))
                drawCircle(colour, r, Offset(w * 0.85f, h / 2), style = Stroke(stroke * 0.8f))
            }
            GameKind.OVERLAP -> {
                drawRoundRect(colour, Offset(0f, h * 0.25f), Size(w * 0.65f, r * 2), CornerRadius(r))
                drawRoundRect(faint, Offset(w * 0.35f, h * 0.6f), Size(w * 0.65f, r * 2), CornerRadius(r))
            }
            GameKind.ODD_ONE_OUT -> {
                for (f in listOf(0.12f, 0.27f, 0.42f)) drawCircle(faint, r, Offset(w * f, h / 2))
                drawCircle(colour, r * 1.3f, Offset(w * 0.86f, h / 2))
            }
            GameKind.CENTURY -> {
                val c = w * 0.42f
                for ((i, o) in listOf(Offset(0f, 0f), Offset(w - c, 0f), Offset(0f, h - c), Offset(w - c, h - c)).withIndex()) {
                    drawRoundRect(if (i == 1) colour else faint, o, Size(c, c), CornerRadius(r))
                }
            }
        }
    }
}
