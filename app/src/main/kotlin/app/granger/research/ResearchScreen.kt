package app.granger.research

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.granger.BuildConfig
import app.granger.feedback.AskReport
import app.granger.feedback.BuildFacts
import app.granger.feedback.FeedbackMail
import app.granger.feedback.ReportSheet
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.ai.ResearchReadiness
import app.granger.core.library.ChatSummary
import app.granger.core.library.TurnOutcome
import app.granger.core.research.MedicalQuotes
import app.granger.core.research.OwlLines
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import app.granger.formatBytes
import app.granger.player.ListenButtons
import app.granger.player.VoiceButton
import app.granger.research.ResearchViewModel.TurnUi
import app.granger.theme.Spectral
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether the owl speaks ([OwlLines]): its lines are English copy, so in another app language Ask uses the plain
 * strings instead (Plan L2; Spanish owl lines wait for the translation review). See ResearchViewModel's `owlSpeaks`.
 */
@Composable
private fun owlSpeaks(): Boolean = LocalConfiguration.current.locales[0].language == "en"

/** How long a source card stays outlined after its citation is tapped. */
private const val FLASH_MS = 1_500L

/** The running turn's progress label: section E's slow line after [OwlLines.SLOW_MS] with no words, then the owl's. */
@Composable
private fun progressLabel(ui: TurnUi, reading: String?): String? {
    var slow by remember(ui.turn.id) { mutableStateOf(false) }
    LaunchedEffect(ui.turn.id) {
        delay(OwlLines.SLOW_MS)
        slow = true
    }
    return when {
        slow && ui.partial.isNullOrBlank() && owlSpeaks() -> OwlLines.SLOW
        reading != null -> reading
        ui.stage == Stage.LOADING_MODEL -> ui.waking ?: ui.stage.label()
        // A medical reply is written out of sight and only chooses the quotes (MedicalQuotes).
        ui.stage == Stage.WRITING && ui.medical && !ui.guessing -> stringResource(R.string.research_stage_quoting)
        else -> ui.stage?.label()
    }
}

/** The owl's instant reaction to the question, in its voice: Spectral italic, until the answer settles. */
@Composable
private fun OwlReaction(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Spectral, fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Stage.label() = stringResource(
    when (this) {
        Stage.LOADING_MODEL -> R.string.research_stage_loading_model
        Stage.SEARCHING -> R.string.research_stage_searching
        Stage.RANKING -> R.string.research_stage_ranking
        Stage.WRITING -> R.string.research_stage_writing
    },
)

/** One row of the thread; [key] keeps rows apart in the list and lets a citation find its source card. */
private sealed interface ThreadRow {
    val key: String

    data class Quote(val turnId: Long, val passage: Passage) : ThreadRow {
        override val key get() = "quote:$turnId"
    }

    data class Question(val turn: TurnUi) : ThreadRow {
        override val key get() = "question:${turn.turn.id}"
    }

    data class Answer(val turn: TurnUi) : ThreadRow {
        override val key get() = "answer:${turn.turn.id}"
    }

    data class Source(val turnId: Long, val n: Int, val passage: Passage) : ThreadRow {
        override val key get() = sourceKey(turnId, n)
    }

    data object Intro : ThreadRow {
        override val key get() = "intro"
    }

}

private fun sourceKey(turnId: Long, n: Int) = "source:$turnId:$n"

/** The thread as list rows: each turn's quote, question, answer and numbered sources; recent chats when empty. */
private fun rows(state: ResearchViewModel.State): List<ThreadRow> = buildList {
    state.turns.forEach { ui ->
        val turn = ui.turn
        turn.shown.firstOrNull()?.takeIf { it.kind == SourceKind.SELECTION }?.let { add(ThreadRow.Quote(turn.id, it)) }
        add(ThreadRow.Question(ui))
        add(ThreadRow.Answer(ui))
        if (turn.outcome == TurnOutcome.ANSWERED || turn.outcome == TurnOutcome.UNVERIFIED) {
            turn.shown.forEachIndexed { i, passage -> add(ThreadRow.Source(turn.id, i + 1, passage)) }
        }
    }
    // A new chat says what Ask is (Plan U3a Decision 8); not over a reader's quote. Recent chats are in the Chats sheet.
    if (state.turns.isEmpty() && state.quote == null) add(ThreadRow.Intro)
}

/**
 * A Research chat: the thread of questions and cited answers, and the question field. [focusInput] focuses the field
 * on open (the reader sent a selection). Opening a cited source hands its [CitationTarget] to [onOpenCitation], except
 * one that is gone, which says so here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(
    viewModel: ResearchViewModel,
    focusInput: Boolean,
    onOpenCitation: (CitationTarget) -> Unit,
    onSearch: (String) -> Unit,
    onGetModels: () -> Unit,
    /** Ask's models and their download, for the empty state; null when the catalog offers none (Settings → Models then). */
    librarian: LibrarianUi?,
    onGetLibrarian: () -> Unit,
    onReadiness: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The models stay loaded while this chat is on screen (started), and five minutes after it leaves.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.shown()
                Lifecycle.Event.ON_STOP -> viewModel.hidden()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.hidden()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val rows = rows(state)
    var flashing by remember { mutableStateOf<String?>(null) }
    val sourceGone = stringResource(R.string.research_source_gone)
    // Follow the reply as it streams, unless the user has scrolled up to read. Saved, like the list's position, so
    // rotation or Back from a citation leaves the thread where the user was.
    var follow by rememberSaveable { mutableStateOf(true) }
    // How many turns this screen has shown; more means a new turn started (Send, Retry) or a chat loaded.
    var seenTurns by rememberSaveable { mutableIntStateOf(-1) }
    // Set by the user's own drags only, so the screen's scrolls never turn following off.
    var userScrolling by remember { mutableStateOf(false) }

    suspend fun scrollToEnd() {
        if (rows.isEmpty()) return
        list.scrollToItem(rows.lastIndex)
        // The list stops at its end; this reaches the bottom of a last row taller than the screen.
        list.scrollBy(Float.MAX_VALUE / 2)
    }

    fun open(turnId: Long, n: Int) {
        scope.launch {
            when (val target = viewModel.open(turnId, n)) {
                CitationTarget.Gone -> snackbar.showSnackbar(sourceGone)
                else -> onOpenCitation(target)
            }
        }
    }

    fun play(turnId: Long, n: Int) {
        scope.launch { if (!viewModel.play(turnId, n)) snackbar.showSnackbar(sourceGone) }
    }

    fun cite(turnId: Long, n: Int) {
        val key = sourceKey(turnId, n)
        val index = rows.indexOfFirst { it.key == key }
        if (index < 0) return
        scope.launch {
            follow = false
            list.animateScrollToItem(index)
            flashing = key
            delay(FLASH_MS)
            if (flashing == key) flashing = null
        }
    }

    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling = true }
    }
    // When the user's drag (and its fling) settles, follow again only if it ended at the bottom.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolling) {
                userScrolling = false
                follow = !list.canScrollForward
            }
        }
    }
    val turnCount = state.turns.size
    LaunchedEffect(turnCount) {
        // Equal after rotation or Back: the restored list position stands. An empty chat (its Recent chats list) stays at the top.
        if (turnCount > 0 && turnCount > seenTurns) {
            follow = true
            scrollToEnd()
        }
        seenTurns = turnCount
    }
    val streamed = state.turns.lastOrNull()?.partial?.length ?: 0
    // A turn that just finished gets one last scroll once its final answer and source cards are stored. They arrive
    // from the database after the stream ends, so the turn counts as live until it is settled (cards present).
    val live = !state.settled
    var wasLive by remember { mutableStateOf(live) }
    LaunchedEffect(rows.size, streamed, live) {
        if (follow && (live || wasLive)) scrollToEnd()
        wasLive = live
    }

    // Report this answer (release checklist A): the turn and whether it's the best guess being reported.
    var reporting by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val context = LocalContext.current
    reporting?.let { (turnId, guess) ->
        val turn = state.turns.firstOrNull { it.turn.id == turnId }?.turn
        if (turn == null) {
            reporting = null
        } else {
            val album = stringResource(R.string.research_source_album)
            val audiobook = stringResource(R.string.research_source_audiobook)
            val sources = if (guess) emptyList() else turn.shown.map { p ->
                listOf(p.sourceTitle, sourceByline(p, album, audiobook)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            ReportSheet(
                guess = guess,
                sources = sources.size,
                onSend = { reason, note ->
                    val report = AskReport(reason, note, turn.question, if (guess) turn.guess else turn.answer, guess, sources, BuildFacts.ofThisPhone())
                    val email = report.intent()
                    try {
                        context.startActivity(FeedbackMail.toMailApps(email, FeedbackMail.mailApps(context)) ?: FeedbackMail.shareInstead(email))
                        reporting = null
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, context.getString(R.string.feedback_no_mail_app, BuildConfig.FEEDBACK_EMAIL), Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = { reporting = null },
            )
        }
    }

    var chatsOpen by remember { mutableStateOf(false) }
    if (chatsOpen) {
        ChatsSheet(
            state.recentChats.filter { it.id != state.chatId },
            onDismiss = { chatsOpen = false },
            onDeleteAll = {
                chatsOpen = false
                viewModel.deleteAllChats()
            },
        ) { id ->
            chatsOpen = false
            onOpenChat(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                title = { AskTitle() },
                actions = {
                    // Recent chats live behind their own button, not in the new chat (Neal, U3a walkthrough).
                    val others = state.recentChats.filter { it.id != state.chatId }
                    if (others.isNotEmpty()) {
                        IconButton(onClick = { chatsOpen = true }) { Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.research_chats)) }
                    }
                    IconButton(onClick = onNewChat) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.research_new_chat)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.research_delete_chat)) },
                                enabled = state.chatId != null,
                                onClick = {
                                    menu = false
                                    viewModel.deleteChat()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                when (state.readiness) {
                    ResearchReadiness.MODELS_MISSING ->
                        if (librarian != null) LibrarianNotice(librarian, onGetLibrarian) else Notice(stringResource(R.string.research_models_missing), stringResource(R.string.research_get_models), onGetModels)
                    ResearchReadiness.UNSUPPORTED -> Notice(stringResource(R.string.research_unsupported))
                    ResearchReadiness.FAILED_CHECK -> {
                        val why by viewModel.checkFailure.collectAsStateWithLifecycle()
                        why?.let { Notice(stringResource(checkText(it)), stringResource(R.string.research_check_retry), viewModel::retryCheck) }
                    }
                    ResearchReadiness.READY -> Unit
                }
                state.quote?.let { QuoteCard(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                QuestionField(
                    viewModel = viewModel,
                    enabled = state.readiness == ResearchReadiness.READY,
                    running = state.running,
                    canSend = viewModel.input.isNotBlank() || state.quote != null,
                    focus = focusInput,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ThreadRow.Quote -> QuoteCard(row.passage, Modifier.padding(horizontal = 16.dp))
                    is ThreadRow.Question -> QuestionBubble(row.turn.turn.question)
                    is ThreadRow.Answer -> AnswerBlock(
                        ui = row.turn,
                        running = state.running,
                        onRead = { viewModel.readAnswer(row.turn.turn.id) },
                        onPauseResume = viewModel::pauseResumeReading,
                        onStopReading = viewModel::stopReading,
                        onCite = { n -> cite(row.turn.turn.id, n) },
                        onSearch = { onSearch(searchInsteadQuery(row.turn.turn.keywords, row.turn.turn.question)) },
                        onRetry = { viewModel.retry(row.turn.turn.id) },
                        onGuess = { viewModel.bestGuess(row.turn.turn.id) },
                        onReport = { guess -> reporting = row.turn.turn.id to guess },
                        onReadiness = onReadiness,
                        checkFailed = state.readiness == ResearchReadiness.FAILED_CHECK,
                    )
                    is ThreadRow.Source -> SourceCard(
                        row.n, row.passage, flashing == row.key, onOpen = { open(row.turnId, row.n) },
                        onPlay = if (viewModel.canPlay && row.passage.kind == SourceKind.MEDIA) ({ play(row.turnId, row.n) }) else null,
                    )
                    ThreadRow.Intro -> Text(
                        stringResource(R.string.research_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
        }
    }
}

/** The question field, with Send, or Stop while a turn runs. */
@Composable
private fun QuestionField(viewModel: ResearchViewModel, enabled: Boolean, running: Boolean, canSend: Boolean, focus: Boolean) {
    val requester = remember { FocusRequester() }
    // Once per chat, not again after rotation or Back.
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (focus && !focused) {
            focused = true
            requester.requestFocus()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = viewModel.input,
            onValueChange = { viewModel.input = it },
            modifier = Modifier.weight(1f).focusRequester(requester),
            enabled = enabled,
            placeholder = { Text(stringResource(R.string.research_placeholder)) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!running && canSend) viewModel.send() }),
        )
        if (running) {
            TextButton(onClick = viewModel::stop) { Text(stringResource(R.string.common_stop)) }
        } else {
            IconButton(onClick = viewModel::send, enabled = enabled && canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.research_send))
            }
        }
    }
}

/** What a turn says: the reply (streaming while it runs), or why there is none, with what to do next. */
@Composable
private fun AnswerBlock(
    ui: TurnUi,
    running: Boolean,
    onRead: () -> Unit,
    onPauseResume: () -> Unit,
    onStopReading: () -> Unit,
    onCite: (Int) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onGuess: () -> Unit,
    onReadiness: () -> Unit,
    /** Report this answer (true: the best guess). */
    onReport: (guess: Boolean) -> Unit = {},
    /** The model failed its self-check: a failed turn points to the notice below, not to Readiness. */
    checkFailed: Boolean = false,
) {
    val turn = ui.turn
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.medical) Banner(stringResource(R.string.research_medical))
        when (turn.outcome) {
            TurnOutcome.PENDING -> {
                ui.reaction?.let { OwlReaction(it) }
                ui.lookingIn?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // Until the first words, the owl says what it is reading (section C); then plain "Writing…".
                val reading = ui.reading?.takeIf { ui.stage == Stage.WRITING && ui.partial.isNullOrBlank() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    progressLabel(ui, reading)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                ui.partial?.takeIf { it.isNotBlank() }?.let {
                    if (ui.medical && MedicalQuotes.isQuoted(it)) QuotedLabel()
                    AnswerBody(it, onCite)
                }
            }
            TurnOutcome.ANSWERED -> {
                if (ui.medical && MedicalQuotes.isQuoted(turn.answer)) QuotedLabel()
                AnswerBody(turn.answer, onCite)
                if (!running || ui.voice != VoiceButton.IDLE) ReadAloudRow(ui.voice, onRead, onPauseResume, onStopReading) { onReport(false) }
            }
            TurnOutcome.UNVERIFIED -> Text(stringResource(R.string.research_unverified), style = MaterialTheme.typography.bodyLarge)
            TurnOutcome.DECLINED -> {
                // The owl's wry decline, steady per turn; a health question keeps the plain words.
                val declined = when {
                    ui.personalMedical -> stringResource(R.string.research_personal_medical)
                    ui.medical || !owlSpeaks() -> stringResource(R.string.research_declined)
                    else -> OwlLines.decline(turn.id)
                }
                Text(declined, style = MaterialTheme.typography.bodyLarge)
                if (ui.guessing && ui.guessShown) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        progressLabel(ui, null)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (ui.guessFailed) {
                    Text(stringResource(R.string.research_model_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onReadiness) { Text(stringResource(R.string.research_readiness)) }
                }
                val guessText = when {
                    !ui.guessShown -> ""
                    ui.guessing -> ui.partial.orEmpty()
                    else -> turn.guess
                }
                if (guessText.isNotBlank()) {
                    GuessCard(if (owlSpeaks()) OwlLines.guessLabel(turn.id) else stringResource(R.string.research_best_guess_label), guessText)
                }
                if (guessText.isNotBlank() && !ui.guessing) ReadAloudRow(ui.voice, onRead, onPauseResume, onStopReading) { onReport(true) }
                if (!(ui.guessing && ui.guessShown)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSearch) { Text(stringResource(R.string.research_search_instead)) }
                        if (ui.offersGuess) {
                            OutlinedButton(onClick = onGuess) {
                                // Still being written out of sight: the tap opens it as it comes.
                                if (ui.guessing) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.research_best_guess))
                            }
                        }
                    }
                }
            }
            TurnOutcome.INTERRUPTED -> {
                Text(if (owlSpeaks()) OwlLines.STOPPED else stringResource(R.string.research_interrupted), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onRetry, enabled = !running) { Text(stringResource(R.string.common_retry)) }
            }
            TurnOutcome.SEARCH_FAILED -> {
                Text(stringResource(R.string.research_search_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !running) { Text(stringResource(R.string.common_retry)) }
            }
            TurnOutcome.FAILED -> if (checkFailed) {
                Text(stringResource(R.string.research_check_turn), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.research_model_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onReadiness) { Text(stringResource(R.string.research_readiness)) }
            }
        }
    }
}

/**
 * Listen under an answer (Plan U2 Decision 6): reads it aloud through the player; while this answer is read, Pause or
 * Play and a separate Stop. Hidden only while a turn runs and nothing is being read.
 */
@Composable
private fun ReadAloudRow(voice: VoiceButton, onRead: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit, onReport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ListenButtons(voice, onRead, onPauseResume, onStop)
        Spacer(Modifier.weight(1f))
        // Report this answer (Play's AI-generated content policy): on every reply and best guess.
        TextButton(onClick = onReport, modifier = Modifier.testTag(REPORT_TAG)) {
            Icon(painterResource(R.drawable.ic_flag), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.report_action))
        }
    }
}

internal const val REPORT_TAG = "research-report"

/** The model's own guess, outlined and set apart from cited answers; it has no sources, so no chips or cards. */
@Composable
private fun GuessCard(label: String, text: String) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun QuestionBubble(question: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            question,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** The reader's selection, quoted above the question it was asked with. */
@Composable
private fun QuoteCard(passage: Passage, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.research_quoted, passage.text.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (passage.sourceTitle.isNotBlank()) {
            Text(passage.sourceTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * Cited passage [n]: its source and the first lines of its text; outlined in the accent while [flash]. [onPlay], for a song,
 * album, chapter or audiobook, shows a Play button that plays it without leaving Research.
 */
@Composable
private fun SourceCard(n: Int, passage: Passage, flash: Boolean, onOpen: () -> Unit, onPlay: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    val byline = sourceByline(passage, stringResource(R.string.research_source_album), stringResource(R.string.research_source_audiobook))
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(if (flash) 2.dp else 1.dp, if (flash) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$n · ${passage.sourceTitle}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onPlay != null) {
                IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_play),
                        contentDescription = stringResource(R.string.research_play_source, passage.heading.ifBlank { passage.sourceTitle }),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (byline.isNotEmpty()) {
            Text(
                byline,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            passage.text.trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Above a medical answer made of the library's own sentences (MedicalQuotes), so it isn't read as the model's words. */
@Composable
private fun QuotedLabel() {
    Text(stringResource(R.string.research_medical_quoted), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

/**
 * The recent chats, from the top bar's Chats button; picking one opens it over this chat, so Back returns here.
 * Delete all chats asks first, then calls [onDeleteAll] (Plan U3b Decision 7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatsSheet(chats: List<ChatSummary>, onDismiss: () -> Unit, onDeleteAll: () -> Unit = {}, onOpen: (Long) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.research_recent_chats),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(chats, key = { it.id }) { chat ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(chat.id) },
                    headlineContent = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.research_delete_all_chats)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.research_delete_all_chats_title)) },
            text = { Text(stringResource(R.string.research_delete_all_chats_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDeleteAll()
                }) { Text(stringResource(R.string.research_delete_all)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_keep)) } },
        )
    }
}

/** "Ask" with its Preview tag (Plan U3a Decisions 7–8). */
@Composable
internal fun AskTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.research_title))
        Text(
            stringResource(R.string.research_preview),
            Modifier
                .padding(start = 10.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The librarian's download in place of a trip to Settings → Models: Get, its progress, or Try again. */
@Composable
internal fun LibrarianNotice(ui: LibrarianUi, onGet: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            ui.downloading -> {
                Text(
                    stringResource(R.string.research_librarian_downloading, formatBytes(ui.bytesDone), formatBytes(ui.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (ui.bytes > 0) (ui.bytesDone.toFloat() / ui.bytes).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ui.failed -> {
                Text(stringResource(R.string.research_librarian_stopped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onGet) { Text(stringResource(R.string.research_try_again)) }
            }
            else -> {
                Text(
                    stringResource(R.string.research_librarian_needed, ui.items.first().title.substringBefore(" ("), formatBytes(ui.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGet) { Text(stringResource(R.string.research_get_librarian, formatBytes(ui.bytes))) }
            }
        }
    }
}

/** Why Ask can't run here, with the way to fix it when there is one. */
@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}
