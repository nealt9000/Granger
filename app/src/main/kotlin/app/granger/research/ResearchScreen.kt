package app.granger.research

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.ai.ResearchReadiness
import app.granger.core.library.ChatSummary
import app.granger.core.library.TurnOutcome
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import app.granger.formatBytes
import app.granger.player.ListenButtons
import app.granger.player.VoiceButton
import app.granger.research.ResearchViewModel.TurnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLACEHOLDER = "Ask about anything in your library"
private const val DECLINED = "Not in your library."
private const val UNVERIFIED = "Couldn't answer from these passages."
private const val MEDICAL = AnswerText.MEDICAL

private const val BEST_GUESS_LABEL = "I couldn't find this in your library. Here's my best guess, so take it with a grain of salt."
private const val MODELS_MISSING = "Ask needs the librarian on this phone. Get it in Settings → Models."
private const val INTRO = "Ask a question; the librarian answers from your library and shows where each part came from. " +
    "It's a preview: it can be wrong, so check the sources."
private const val MODEL_FAILED = "The model needs repair. Check it in Readiness."
private const val SEARCH_FAILED = "Couldn't search your library. Try again."
private const val INTERRUPTED = "Stopped before an answer."
private const val SOURCE_GONE = "That source is no longer in your library."

/** How long a source card stays outlined after its citation is tapped. */
private const val FLASH_MS = 1_500L

private fun Stage.label() = when (this) {
    Stage.LOADING_MODEL -> "Loading the model…"
    Stage.SEARCHING -> "Searching your library…"
    Stage.RANKING -> "Ranking passages…"
    Stage.WRITING -> "Writing…"
}

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
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    val rows = rows(state)
    var flashing by remember { mutableStateOf<String?>(null) }
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
                CitationTarget.Gone -> snackbar.showSnackbar(SOURCE_GONE)
                else -> onOpenCitation(target)
            }
        }
    }

    fun play(turnId: Long, n: Int) {
        scope.launch { if (!viewModel.play(turnId, n)) snackbar.showSnackbar(SOURCE_GONE) }
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
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { AskTitle() },
                actions = {
                    // Recent chats live behind their own button, not in the new chat (Neal, U3a walkthrough).
                    val others = state.recentChats.filter { it.id != state.chatId }
                    if (others.isNotEmpty()) {
                        IconButton(onClick = { chatsOpen = true }) { Icon(painterResource(R.drawable.ic_history), contentDescription = "Chats") }
                    }
                    IconButton(onClick = onNewChat) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
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
                        if (librarian != null) LibrarianNotice(librarian, onGetLibrarian) else Notice(MODELS_MISSING, "Get models", onGetModels)
                    ResearchReadiness.UNSUPPORTED -> Notice("Ask needs a phone with 6 GB of memory.")
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
                        onReadiness = onReadiness,
                    )
                    is ThreadRow.Source -> SourceCard(
                        row.n, row.passage, flashing == row.key, onOpen = { open(row.turnId, row.n) },
                        onPlay = if (viewModel.canPlay && row.passage.kind == SourceKind.MEDIA) ({ play(row.turnId, row.n) }) else null,
                    )
                    ThreadRow.Intro -> Text(
                        INTRO,
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
            placeholder = { Text(PLACEHOLDER) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!running && canSend) viewModel.send() }),
        )
        if (running) {
            TextButton(onClick = viewModel::stop) { Text("Stop") }
        } else {
            IconButton(onClick = viewModel::send, enabled = enabled && canSend) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
) {
    val turn = ui.turn
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.medical) Banner(MEDICAL)
        when (turn.outcome) {
            TurnOutcome.PENDING -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    ui.stage?.let { Text(it.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                ui.partial?.takeIf { it.isNotBlank() }?.let { AnswerBody(it, onCite) }
            }
            TurnOutcome.ANSWERED -> {
                AnswerBody(turn.answer, onCite)
                if (!running || ui.voice != VoiceButton.IDLE) ReadAloudRow(ui.voice, onRead, onPauseResume, onStopReading)
            }
            TurnOutcome.UNVERIFIED -> Text(UNVERIFIED, style = MaterialTheme.typography.bodyLarge)
            TurnOutcome.DECLINED -> {
                Text(DECLINED, style = MaterialTheme.typography.bodyLarge)
                if (ui.guessing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        ui.stage?.let { Text(it.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (ui.guessFailed) {
                    Text(MODEL_FAILED, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onReadiness) { Text("Readiness") }
                }
                val guessText = if (ui.guessing) ui.partial.orEmpty() else turn.guess
                if (guessText.isNotBlank()) GuessCard(guessText)
                if (guessText.isNotBlank() && !ui.guessing) ReadAloudRow(ui.voice, onRead, onPauseResume, onStopReading)
                if (!ui.guessing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSearch) { Text("Search instead") }
                        if (ui.offersGuess) OutlinedButton(onClick = onGuess, enabled = !running) { Text("Best guess") }
                    }
                }
            }
            TurnOutcome.INTERRUPTED -> {
                Text(INTERRUPTED, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onRetry, enabled = !running) { Text("Retry") }
            }
            TurnOutcome.SEARCH_FAILED -> {
                Text(SEARCH_FAILED, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !running) { Text("Retry") }
            }
            TurnOutcome.FAILED -> {
                Text(MODEL_FAILED, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onReadiness) { Text("Readiness") }
            }
        }
    }
}

/**
 * Listen under an answer (Plan U2 Decision 6): reads it aloud through the player; while this answer is read, Pause or
 * Play and a separate Stop. Hidden only while a turn runs and nothing is being read.
 */
@Composable
private fun ReadAloudRow(voice: VoiceButton, onRead: () -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { ListenButtons(voice, onRead, onPauseResume, onStop) }
}

/** The model's own guess, outlined and set apart from cited answers; it has no sources, so no chips or cards. */
@Composable
private fun GuessCard(text: String) {
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
            BEST_GUESS_LABEL,
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
            "“${passage.text.trim()}”",
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
    val byline = sourceByline(passage)
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
                        contentDescription = "Play ${passage.heading.ifBlank { passage.sourceTitle }}",
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
            "Recent chats",
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
        TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Delete all chats") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every chat goes, this one too, with its answers and sources.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDeleteAll()
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

/** "Ask" with its Preview tag (Plan U3a Decisions 7–8). */
@Composable
internal fun AskTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Ask")
        Text(
            "Preview",
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
                    "Downloading the librarian · ${formatBytes(ui.bytesDone)} of ${formatBytes(ui.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (ui.bytes > 0) (ui.bytesDone.toFloat() / ui.bytes).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ui.failed -> {
                Text("The librarian's download stopped.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onGet) { Text("Try again") }
            }
            else -> {
                Text(
                    "Ask needs the librarian on this phone: ${ui.items.first().title.substringBefore(" (")}, ${formatBytes(ui.bytes)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onGet) { Text("Get the librarian · ${formatBytes(ui.bytes)}") }
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
