package app.granger.research

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.BookTarget
import app.granger.core.ai.ModelManager
import app.granger.core.ai.ResearchReadiness
import app.granger.core.books.TextQuote
import app.granger.core.library.BookIds
import app.granger.core.library.Bookshelf
import app.granger.core.library.ChatSummary
import app.granger.core.library.ChatTurn
import app.granger.core.library.Library
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ResearchChats
import app.granger.core.library.TurnOutcome
import app.granger.core.research.FailReason
import app.granger.core.research.Outcome
import app.granger.core.research.Passage
import app.granger.core.research.PriorTurn
import app.granger.core.research.ResearchEvent
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.ResearchRequest
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asked when the user sends a quote without typing a question. */
const val EMPTY_QUOTE_QUESTION = "Explain this passage and how it connects to the rest of my library."

/** Whether a best guess may use the large model (when the phone's tier and the installed models allow it). */
const val BEST_GUESS_USES_LARGE = true

/**
 * One Research chat: questions and follow-ups answered from the library, Stop, Think deeper and Retry. [chatId] opens a
 * kept chat (null starts a new one on the first send); [quote] is the reader's selection, used by the first question.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResearchViewModel(
    chatId: Long?,
    quote: Passage?,
    private val chats: ResearchChats,
    private val pipeline: ResearchPipeline,
    private val models: ModelManager,
    private val sessions: ReadingSessions,
    private val library: Library,
    private val bookshelf: Bookshelf,
    /** Settles a turn cut short when this view model is gone. */
    private val appScope: CoroutineScope,
    /** Survives process death with the route: the chat this one became, and that the route's [quote] was asked. */
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** Runs the pipeline's own work (prompt building, ranking, citation checks) off the main thread. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /**
     * A stored turn, with the reply so far, the stage and the large-model fallback while it is running. [medical] shows
     * the banner, from the pipeline while the turn runs (the stored turn learns it only when settled).
     */
    data class TurnUi(
        val turn: ChatTurn,
        val partial: String?,
        val stage: Stage?,
        val largeFallback: Boolean,
        val medical: Boolean,
        /** A best guess is being written for this (declined) turn; [partial] is the guess so far. */
        val guessing: Boolean = false,
        /** The Best guess button is shown: a declined, non-medical turn with no guess yet, while nothing runs. */
        val offersGuess: Boolean = false,
        /** The guess being written uses the large model (it takes about half a minute). */
        val guessLarge: Boolean = false,
        /** The large model couldn't load, so the guess is from the standard one. */
        val guessFellBack: Boolean = false,
        /** The last Best guess for this turn failed (the model won't load); shown once, not stored. */
        val guessFailed: Boolean = false,
    )

    data class State(
        val chatId: Long?,
        val title: String,
        val turns: List<TurnUi>,
        val running: Boolean,
        val readiness: ResearchReadiness,
        /** Think deeper is offered under the last turn. */
        val canThinkDeeper: Boolean,
        /** The selection waiting for the first question; null once it is asked. */
        val quote: Passage?,
        /** The last [RECENT_CHATS] chats, shown while this one is empty. */
        val recentChats: List<ChatSummary>,
    ) {
        /**
         * The last turn is done and its source cards are present, so the screen's last scroll can run. An answered turn
         * whose cards haven't arrived from the database yet (after a stream or a Retry) is not settled.
         */
        val settled: Boolean
            get() {
                if (running) return false
                val last = turns.lastOrNull()?.turn ?: return true
                return when (last.outcome) {
                    TurnOutcome.PENDING -> false
                    TurnOutcome.ANSWERED, TurnOutcome.UNVERIFIED -> last.shown.isNotEmpty()
                    else -> true
                }
            }
    }

    /** The running turn; [turnId] is null until its row exists. */
    private data class Live(
        val turnId: Long?,
        val partial: String?,
        val stage: Stage?,
        val largeFallback: Boolean,
        val medical: Boolean = false,
        val guessing: Boolean = false,
        val guessLarge: Boolean = false,
        val guessFellBack: Boolean = false,
    )

    private data class Header(val chatId: Long?, val title: String, val quote: Passage?, val recent: List<ChatSummary>)

    /** What to ask; [quote] and [deeperFrom] as in [ResearchRequest]. */
    private class Ask(
        val question: String,
        val quote: Passage?,
        val askedDeeper: Boolean = false,
        val deeperFrom: List<Passage>? = null,
        val medicalHint: Boolean = false,
    )

    // A saved id wins over the route's, even a null one: the chat was deleted.
    private val startChat = if (savedState.contains(CHAT_ID)) savedState.get<Long>(CHAT_ID) else chatId
    private val startQuote = quote.takeUnless { savedState.get<Boolean>(QUOTE_USED) == true }
    private val chat = MutableStateFlow(startChat)
    private val title = MutableStateFlow("")
    private val pendingQuote = MutableStateFlow(startQuote)
    private val live = MutableStateFlow<Live?>(null)
    /** The turn whose Best guess just failed; cleared when anything else starts. */
    private val guessFailed = MutableStateFlow<Long?>(null)
    private var job: Job? = null
    @Volatile private var stopRequested = false

    /** The question field, as Compose state written synchronously on every keystroke (see SearchViewModel.searchText). */
    var input by mutableStateOf("")

    // A storage error shows an empty thread (and no recent chats) rather than crashing the screen.
    private val turns = chat.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chats.turns(id).emptyOnError("Research turns")
    }

    val state: StateFlow<State> = combine(
        turns,
        live,
        combine(models.readiness, models.canThinkDeeper, guessFailed, ::Triple),
        combine(chat, title, pendingQuote, chats.recent(RECENT_CHATS).emptyOnError("Recent research chats"), ::Header),
    ) { turns, live, (readiness, deeperInstalled, guessFailedId), header ->
        val last = turns.lastOrNull()
        State(
            chatId = header.chatId,
            title = header.title,
            turns = turns.map { it.toUi(live, guessFailedId) },
            running = live != null,
            readiness = readiness,
            canThinkDeeper = deeperInstalled && live == null && last != null && last.canDeepen(),
            quote = header.quote,
            recentChats = header.recent,
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        State(startChat, "", emptyList(), false, models.readiness.value, false, startQuote, emptyList()),
    )

    init {
        if (startChat != null) viewModelScope.launch { logErrors { title.value = chats.chat(startChat)?.title.orEmpty() } }
    }

    /** Asks what is in [input]; a blank input asks [EMPTY_QUOTE_QUESTION] about the quote, or does nothing without one. */
    fun send() {
        if (live.value != null) return
        val quote = pendingQuote.value
        val typed = input.trim().take(MAX_QUESTION).trimEnd()
        val question = typed.ifEmpty { if (quote != null) EMPTY_QUOTE_QUESTION else return }
        input = ""
        pendingQuote.value = null
        if (quote != null) savedState[QUOTE_USED] = true
        val newTitle = if (quote != null && question == EMPTY_QUOTE_QUESTION) {
            "About: " + quote.text.trim().take(QUOTE_TITLE).trimEnd()
        } else {
            cutAtWord(question, TITLE)
        }
        begin {
            val id = chat.value ?: chats.create(newTitle).also {
                title.value = newTitle
                chat.value = it
                savedState[CHAT_ID] = it
            }
            runTurn(id, Ask(question, quote), before = chats.turns(id).first())
        }
    }

    /**
     * Stops the running turn: once the reply has begun, what it has so far is checked and kept; before that (still
     * reading the prompt, with no chunk to stop after), the turn is interrupted.
     */
    fun stop() {
        val running = live.value ?: return
        if (running.stage == Stage.WRITING && running.partial != null) stopRequested = true else job?.cancel()
    }

    /** Asks the last turn's question again with the large model, over the passages it already found. */
    fun thinkDeeper(turnId: Long) {
        if (live.value != null || !models.canThinkDeeper.value) return
        val id = chat.value ?: return
        begin {
            val turns = chats.turns(id).first()
            val turn = turns.lastOrNull()?.takeIf { it.id == turnId && it.canDeepen() } ?: return@begin
            val from = (turn.shown + turn.candidates).filter { it.kind != SourceKind.SELECTION }.take(DEEPER_PASSAGES)
            val ask = Ask(turn.question, turn.quote(), askedDeeper = true, deeperFrom = from, medicalHint = turn.medical)
            runTurn(id, ask, before = turns.dropLast(1))
        }
    }

    /**
     * Asks the model for its own best guess at a declined turn's question. The guess is kept on that turn when it
     * finishes or is stopped; if the app dies first nothing is kept and the button returns. Never for a medical turn.
     */
    fun bestGuess(turnId: Long) {
        if (live.value != null) return
        val id = chat.value ?: return
        begin {
            val turns = chats.turns(id).first()
            val at = turns.indexOfFirst { it.id == turnId }
            val turn = turns.getOrNull(at)?.takeIf { it.outcome == TurnOutcome.DECLINED && it.guess.isBlank() && !it.medical } ?: return@begin
            val large = BEST_GUESS_USES_LARGE && models.canThinkDeeper.value
            live.value = Live(turnId, null, null, false, guessing = true, guessLarge = large)
            var fellBack = false
            var settled = false
            // The guess goes to the model alone: no passages, and no earlier answer or guess, only the question before it.
            pipeline.guess(turn.question, turns.getOrNull(at - 1)?.question, large) { stopRequested }.flowOn(cpu).collect { event ->
                when (event) {
                    is ResearchEvent.Progress -> live.update { it?.copy(stage = event.stage) }
                    is ResearchEvent.Partial -> live.update { it?.copy(partial = event.text) }
                    ResearchEvent.GuessFallback -> {
                        fellBack = true
                        live.update { it?.copy(guessFellBack = true) }
                    }
                    is ResearchEvent.Done -> {
                        val outcome = event.outcome
                        if (outcome is Outcome.Guessed && !settled) {
                            settled = true
                            withContext(NonCancellable) { chats.setGuess(turnId, outcome.text, outcome.usedLarge, outcome.fellBack || fellBack) }
                        } else if (outcome is Outcome.Failed) {
                            outcome.cause?.let { runCatching { android.util.Log.w("Granger", "Research guess failed", it) } }
                            // Only the model can fail a guess (it does no search); say so, without storing it.
                            guessFailed.value = turnId
                        }
                    }
                    // run() events; guess() doesn't emit them.
                    ResearchEvent.Medical, ResearchEvent.LargeFallback -> Unit
                }
            }
        }
    }

    /** Asks an interrupted or failed turn's question again, as a new turn. */
    fun retry(turnId: Long) {
        if (live.value != null) return
        val id = chat.value ?: return
        begin {
            val turns = chats.turns(id).first()
            val at = turns.indexOfFirst { it.id == turnId && it.outcome in RETRYABLE }
            if (at < 0) return@begin
            val turn = turns[at]
            runTurn(id, Ask(turn.question, turn.quote()), before = turns.take(at))
        }
    }

    /**
     * Opens the cited passage's source in a rail tab, at the passage where it can; [CitationTarget.Gone] when it's gone,
     * or can't be opened (a storage error is logged, not thrown into the screen's tap handler).
     */
    suspend fun open(passage: Passage): CitationTarget = try {
        target(passage)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { android.util.Log.w("Granger", "Research citation couldn't be opened", e) }
        CitationTarget.Gone
    }

    private suspend fun target(passage: Passage): CitationTarget {
        if (!BookIds.isBook(passage.sourceId)) {
            val pack = library.packs.first().firstOrNull { it.id == passage.sourceId }
            if (pack == null || pack.status != PackStatus.OK) return CitationTarget.Gone
            // A catalogue card describes the whole pack, so it opens the pack's main page.
            val card = passage.kind == SourceKind.CARD
            val tabId = sessions.openTab(pack.id, if (card) "" else passage.location, passage.sourceTitle)
            // The tab can vanish between opening and showing it (closed from the rail): the source is gone, say so.
            if (citationTab(sessions, tabId) == null) return CitationTarget.Gone
            return CitationTarget.Article(tabId, if (card) null else passage.anchor)
        }
        val book = bookshelf.book(passage.sourceId)
        if (book == null || book.status != PackStatus.OK) return CitationTarget.Gone
        val tabId = sessions.openTab(book.id, "", book.title)
        if (citationTab(sessions, tabId) == null) return CitationTarget.Gone
        val placed = passage.kind == SourceKind.BOOK || (passage.kind == SourceKind.SELECTION && passage.location.isNotEmpty())
        if (!placed) return CitationTarget.Book(tabId, at = null)
        val quote = TextQuote("", cutAtWord(passage.text.trim(), BOOK_QUOTE), "")
        return CitationTarget.Book(tabId, BookTarget(passage.location, passage.progression, quote))
    }

    /** Deletes this chat (stopping a running turn) and leaves the view empty. */
    fun deleteChat() {
        val id = chat.value ?: return
        job?.cancel()
        chat.value = null
        savedState[CHAT_ID] = null
        title.value = ""
        appScope.launch { logErrors { chats.delete(id) } }
    }

    override fun onCleared() {
        job?.cancel()
        models.releaseSoon()
    }

    /**
     * Runs [work] as the one running turn; nothing else starts until it ends. The turn holds the models, so a release
     * asked for meanwhile (the app going to the background) waits for its end rather than reloading between stages;
     * after it, they go after a quiet spell, so a chat left open (or under a reader) doesn't keep them.
     */
    private fun begin(work: suspend () -> Unit) {
        stopRequested = false
        guessFailed.value = null
        live.value = Live(null, null, null, false)
        job = viewModelScope.launch {
            val letGo = models.hold()
            try {
                logErrors { work() }
            } finally {
                live.value = null
                // Asked while still held, so a background release waiting on the hold isn't replaced by the timer.
                models.releaseSoon()
                letGo()
            }
        }
    }

    /** Runs [block], logging a storage error instead of crashing: the chat stays usable and Send works again. */
    private suspend fun logErrors(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.w("Granger", "Research chat storage failed", e) }
        }
    }

    /** Stores a PENDING turn, runs the pipeline and settles the turn; [before] are the turns it follows on from. */
    private suspend fun runTurn(chatId: Long, ask: Ask, before: List<ChatTurn>) {
        val prior = before.lastOrNull { it.outcome == TurnOutcome.ANSWERED }?.let { PriorTurn(it.question, it.answer) }
        val request = ResearchRequest(ask.question, ask.quote, prior, before.lastOrNull()?.question, ask.deeperFrom, ask.medicalHint)
        val kept = listOfNotNull(ask.quote)
        var turnId: Long? = null
        var settled = false
        var fellBack = false
        // The pipeline's verdict once known (it can be ahead of the stored turn); a turn cut short keeps it.
        var medical = ask.medicalHint
        try {
            // Not cancellable, so a turn that was stored is always known here and can be settled.
            withContext(NonCancellable) {
                turnId = chats.startTurn(chatId, ask.question, ask.askedDeeper, ask.quote)
                live.value = Live(turnId, null, null, false)
            }
            pipeline.run(request) { stopRequested }.flowOn(cpu).collect { event ->
                when (event) {
                    is ResearchEvent.Progress -> live.update { it?.copy(stage = event.stage) }
                    is ResearchEvent.Partial -> live.update { it?.copy(partial = event.text) }
                    ResearchEvent.Medical -> {
                        medical = true
                        live.update { it?.copy(medical = true) }
                    }
                    ResearchEvent.LargeFallback -> {
                        fellBack = true
                        live.update { it?.copy(largeFallback = true) }
                    }
                    ResearchEvent.GuessFallback -> Unit // Only guess() emits this; see bestGuess.
                    is ResearchEvent.Done -> withContext(NonCancellable) {
                        (event.outcome as? Outcome.Failed)?.cause?.let { runCatching { android.util.Log.w("Granger", "Research turn failed", it) } }
                        settle(checkNotNull(turnId), event.outcome, kept, usedLarge = ask.askedDeeper && !fellBack)
                        settled = true
                    }
                }
            }
        } catch (e: CancellationException) {
            val id = turnId
            if (id != null && !settled) {
                appScope.launch {
                    logErrors { chats.finishTurn(id, TurnOutcome.INTERRUPTED, "", medical, false, emptyList(), kept, emptyList()) }
                }
            }
            throw e
        } catch (e: Exception) {
            // A model or search error mid-run: the turn failed, and Readiness can check the model.
            runCatching { android.util.Log.w("Granger", "Research turn failed", e) }
            val id = turnId
            if (id != null && !settled) {
                // The error may be the database's own; the turn then stays PENDING until the next launch interrupts it.
                withContext(NonCancellable) {
                    logErrors { chats.finishTurn(id, TurnOutcome.FAILED, "", medical, false, emptyList(), kept, emptyList()) }
                }
            }
        }
    }

    /** [usedLarge] is for outcomes that don't carry it. Declined and failed turns keep only the quote. */
    private suspend fun settle(turnId: Long, outcome: Outcome, kept: List<Passage>, usedLarge: Boolean) {
        when (outcome) {
            is Outcome.Answered -> chats.finishTurn(
                turnId, TurnOutcome.ANSWERED, outcome.text, outcome.medical, outcome.usedLarge, outcome.keywords,
                outcome.passages, outcome.candidates,
            )
            is Outcome.Unverified -> chats.finishTurn(
                turnId, TurnOutcome.UNVERIFIED, outcome.text, outcome.medical, outcome.usedLarge, outcome.keywords,
                outcome.passages, outcome.candidates,
            )
            is Outcome.Declined ->
                chats.finishTurn(turnId, TurnOutcome.DECLINED, "", outcome.medical, usedLarge, outcome.keywords, kept, emptyList())
            is Outcome.Failed -> {
                val kind = if (outcome.reason == FailReason.SEARCH_FAILED) TurnOutcome.SEARCH_FAILED else TurnOutcome.FAILED
                chats.finishTurn(turnId, kind, "", outcome.medical, false, outcome.keywords, kept, emptyList())
            }
            // Only ResearchPipeline.guess produces this, and bestGuess keeps it; a turn run() settles this way failed.
            is Outcome.Guessed -> chats.finishTurn(turnId, TurnOutcome.FAILED, "", false, false, emptyList(), kept, emptyList())
        }
    }

    private fun ChatTurn.toUi(live: Live?, guessFailedId: Long?): TurnUi {
        val running = live?.takeIf { it.turnId == id }
        return TurnUi(
            turn = this,
            partial = running?.partial,
            stage = running?.stage,
            largeFallback = running?.largeFallback ?: (askedDeeper && !usedLarge && outcome in FALLBACK_SHOWN),
            medical = medical || running?.medical == true,
            guessing = running?.guessing == true,
            offersGuess = live == null && outcome == TurnOutcome.DECLINED && guess.isBlank() && !medical,
            guessLarge = running?.guessLarge == true,
            guessFellBack = running?.guessFellBack ?: guessFellBack,
            guessFailed = guessFailedId == id && live == null,
        )
    }

    private fun ChatTurn.canDeepen() = outcome in DEEPENABLE && !usedLarge

    /** The selection this turn was asked about, kept as its first shown passage. */
    private fun ChatTurn.quote(): Passage? = shown.firstOrNull()?.takeIf { it.kind == SourceKind.SELECTION }

    companion object {
        const val CHAT_ID = "chatId"
        const val QUOTE_USED = "quoteUsed"
        const val RECENT_CHATS = 10
        /** Longer questions are cut, so a pasted wall of text can't overflow the prompt. */
        const val MAX_QUESTION = 1_000
        const val TITLE = 60
        const val QUOTE_TITLE = 40
        const val BOOK_QUOTE = 80
        const val DEEPER_PASSAGES = 8
        private val DEEPENABLE = setOf(TurnOutcome.ANSWERED, TurnOutcome.UNVERIFIED)
        private val RETRYABLE = setOf(TurnOutcome.INTERRUPTED, TurnOutcome.FAILED, TurnOutcome.SEARCH_FAILED)
        private val FALLBACK_SHOWN = setOf(TurnOutcome.ANSWERED, TurnOutcome.UNVERIFIED, TurnOutcome.DECLINED)
    }
}

/** [text] cut to at most [max] characters, at the last space when there is one. */
internal fun cutAtWord(text: String, max: Int): String {
    if (text.length <= max) return text
    // A cut that lands just before a space keeps the whole last word.
    if (text[max].isWhitespace()) return text.take(max).trimEnd()
    val cut = text.take(max)
    val space = cut.lastIndexOf(' ')
    return (if (space > 0) cut.take(space) else cut).trimEnd()
}

/** This flow, with a storage error logged and replaced by an empty list instead of reaching the collector. */
private fun <T> Flow<List<T>>.emptyOnError(what: String): Flow<List<T>> = catch { e ->
    if (e is CancellationException) throw e
    runCatching { android.util.Log.w("Granger", "$what couldn't be read", e) }
    emit(emptyList())
}
