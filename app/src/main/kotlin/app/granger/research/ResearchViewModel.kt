package app.granger.research

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.audiobooks.AudiobookShelf
import app.granger.core.ai.ModelManager
import app.granger.core.ai.ResearchReadiness
import app.granger.core.library.Bookshelf
import app.granger.core.library.ChatSummary
import app.granger.core.library.ChatTurn
import app.granger.core.library.Library
import app.granger.core.library.MediaIndex
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ResearchChats
import app.granger.core.library.TurnOutcome
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlaySource
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerUi
import app.granger.core.speech.StreamingTextReadSource
import app.granger.core.speech.TextReadSource
import app.granger.player.VoiceButton
import app.granger.voiceinput.Dictation
import app.granger.voiceinput.VoiceInput
import kotlinx.coroutines.flow.map
import app.granger.core.research.FailReason
import app.granger.core.research.Keywords
import app.granger.core.research.Outcome
import app.granger.core.research.OwlLines
import app.granger.core.research.Passage
import app.granger.core.research.PersonalMedical
import app.granger.core.research.PriorTurn
import app.granger.core.research.ReactionTopic
import app.granger.core.research.ResearchEvent
import app.granger.core.research.ResearchLanguage
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * One Research chat: questions and follow-ups answered from the library, Stop, Best guess and Retry. [chatId] opens a
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
    /** Says whether a cited album or audiobook is still present (a media citation opens its page). */
    private val media: MediaIndex,
    /** Settles a turn cut short when this view model is gone. */
    private val appScope: CoroutineScope,
    /** Survives process death with the route: the chat this one became, and that the route's [quote] was asked. */
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /** Runs the pipeline's own work (prompt building, ranking, citation checks) off the main thread. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    /** Reads answers aloud (Plan V2) and plays cited songs and chapters (Plan R3); null leaves both out. */
    private val player: PlayerHub? = null,
    /**
     * Speak replies: the ask box's speaker, the same setting as Settings → Voice → "Speak answers as they arrive". A
     * new reply is read as it arrives, the owl's lines first.
     */
    private val speakAnswers: StateFlow<Boolean> = MutableStateFlow(false),
    private val setSpeakAnswers: (Boolean) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Whether the owl speaks ([OwlLines]): its lines are English copy, so with the app in another language Ask shows the
     * plain progress labels and declines instead (Plan L2; Spanish owl lines wait for the translation review).
     */
    private val owlSpeaks: () -> Boolean = { java.util.Locale.getDefault().language == "en" },
    /** Voice input (Plan A1); null leaves the ask box without a mic. */
    private val voice: VoiceInput? = null,
    /**
     * In conversation mode, what is said for a turn that has no streamed answer to read (a decline, a failure), in the
     * screen's words; null says nothing and listens again.
     */
    private val outcomeLine: (TurnUi) -> String? = { null },
    /**
     * Whether Ask answers a question in Spanish ([Features.SPANISH_ASK]); when it doesn't, a Spanish-led question
     * ([ResearchLanguage.main]) is declined at once as English only, with no search, model run or best guess.
     */
    private val spanishAsk: Boolean = app.granger.Features.SPANISH_ASK,
) : ViewModel() {
    /** The answer the voice is on ([turnId]) and whether it is speaking; null when the voice is elsewhere or has run out. */
    data class Reading(val turnId: Long, val playing: Boolean)

    /**
     * A stored turn, with the reply so far and the stage while it is running. [medical] shows the banner, from the
     * pipeline while the turn runs (the stored turn learns it only when settled).
     */
    data class TurnUi(
        val turn: ChatTurn,
        val partial: String?,
        val stage: Stage?,
        val medical: Boolean,
        /** The owl's instant reaction while the turn runs; not stored. */
        val reaction: String? = null,
        /** Where the owl is looking ("Looking in Boiling · Steam…") while the turn runs; not stored. */
        val lookingIn: String? = null,
        /** What the owl is reading ("Reading 4 passages…"), from just before the answer streams; not stored. */
        val reading: String? = null,
        /** The owl's waking line while the model loads, one per answer; not stored. */
        val waking: String? = null,
        /** A best guess is being written for this (declined) turn; [partial] is the guess so far. */
        val guessing: Boolean = false,
        /**
         * The Best guess button is shown: a declined, non-medical turn whose guess isn't open yet, and either has one
         * (kept or being written) or can start one because nothing runs.
         */
        val offersGuess: Boolean = false,
        /** The guess is open (Neal, 2026-11-02: it sits behind a tap, written ahead so the tap shows it at once). */
        val guessShown: Boolean = false,
        /** Declined as one person's own health question (no search ran): the screen points to a professional. */
        val personalMedical: Boolean = false,
        /** Declined because it's in Spanish and Spanish Ask is off ([Features.SPANISH_ASK]); no search ran. */
        val englishOnly: Boolean = false,
        /** The last Best guess for this turn failed (the model won't load); shown once, not stored. */
        val guessFailed: Boolean = false,
        /** The speaker button's state for this turn (Plan V2): read, pause or resume. */
        val voice: VoiceButton = VoiceButton.IDLE,
    )

    data class State(
        val chatId: Long?,
        val title: String,
        val turns: List<TurnUi>,
        val running: Boolean,
        val readiness: ResearchReadiness,
        /** The selection waiting for the first question; null once it is asked. */
        val quote: Passage?,
        /** The last [RECENT_CHATS] chats, shown while this one is empty. */
        val recentChats: List<ChatSummary>,
    ) {
        /**
         * The last turn is done and its source cards are present, so the screen's last scroll can run. An answered turn
         * is not settled while it runs or its row is still PENDING; the stored row carries the outcome and the cards together.
         */
        val settled: Boolean
            get() {
                if (running) return false
                val last = turns.lastOrNull()?.turn ?: return true
                return when (last.outcome) {
                    TurnOutcome.PENDING -> false
                    // A final outcome is settled. Its cards arrive in the same stored row, so the only wait is the
                    // stream's end (running) and the PENDING row turning final; a turn with no cards is settled too.
                    else -> true
                }
            }
    }

    /** The running turn; [turnId] is null until its row exists. */
    private data class Live(
        val turnId: Long?,
        val partial: String?,
        val stage: Stage?,
        val medical: Boolean = false,
        val guessing: Boolean = false,
        val reaction: String? = null,
        val lookingIn: String? = null,
        val reading: String? = null,
        val waking: String? = null,
    )

    private data class Header(val chatId: Long?, val title: String, val quote: Passage?, val recent: List<ChatSummary>)

    /** What to ask; [quote] as in [ResearchRequest]. */
    private class Ask(val question: String, val quote: Passage?)

    // A saved id wins over the route's, even a null one: the chat was deleted.
    private val startChat = if (savedState.contains(CHAT_ID)) savedState.get<Long>(CHAT_ID) else chatId
    private val startQuote = quote.takeUnless { savedState.get<Boolean>(QUOTE_USED) == true }
    private val chat = MutableStateFlow(startChat)
    private val title = MutableStateFlow("")
    private val pendingQuote = MutableStateFlow(startQuote)
    private val live = MutableStateFlow<Live?>(null)
    /** The turn whose Best guess just failed; cleared when anything else starts. */
    private val guessFailed = MutableStateFlow<Long?>(null)
    /** Turns whose guess was opened with the button; not stored, so a reopened chat shows the button again. */
    private val shownGuesses = MutableStateFlow<Set<Long>>(emptySet())
    private var job: Job? = null
    @Volatile private var stopRequested = false
    /** The owl's lines said in this chat, so it doesn't repeat itself until it has said them all. */
    private val saidLines = mutableSetOf<String>()

    /** A waking line for a new answer or guess, not one this chat has had until all have been. */
    private fun wakingLine(): String? =
        if (owlSpeaks()) OwlLines.pickFrom(OwlLines.WAKING, saidLines).also { saidLines += it } else null

    /** The question field, as Compose state written synchronously on every keystroke (see SearchViewModel.searchText). */
    var input by mutableStateOf("")

    // A storage error shows an empty thread (and no recent chats) rather than crashing the screen.
    private val turns = chat.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chats.turns(id).emptyOnError("Research turns")
    }

    /** Which answer the voice is reading, from the player (Plan V2). */
    private val reading: Flow<Reading?> = (player?.ui ?: flowOf(PlayerUi.IDLE)).map { ui ->
        val voice = ui.now as? NowPlaying.Voice ?: return@map null
        if (voice.ended) null else answerTurnId(voice.sourceKey)?.let { Reading(it, ui.playing) }
    }

    val state: StateFlow<State> = combine(
        turns,
        live,
        combine(models.readiness, guessFailed, shownGuesses, ::Triple),
        combine(chat, title, pendingQuote, chats.recent(RECENT_CHATS).emptyOnError("Recent research chats"), ::Header),
        reading,
    ) { turns, live, (readiness, guessFailedId, shown), header, reading ->
        State(
            chatId = header.chatId,
            title = header.title,
            turns = turns.map { it.toUi(live, guessFailedId, shown, reading) },
            running = live != null,
            readiness = readiness,
            quote = header.quote,
            recentChats = header.recent,
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        State(startChat, "", emptyList(), false, models.readiness.value, startQuote, emptyList()),
    )

    init {
        if (startChat != null) viewModelScope.launch { logErrors { title.value = chats.chat(startChat)?.title.orEmpty() } }
    }

    /** Asks what is in [input]; a blank input asks [EMPTY_QUOTE_QUESTION] about the quote, or does nothing without one. */
    /** Speaking a question (Plan A1): null when there's no mic. */
    val dictation: Dictation? = voice?.let { Dictation(it, viewModelScope) }

    /**
     * Whether the ask box shows its mic: the phone can listen in the app's language, and Ask answers in it. Ask is
     * English-only until Spanish Ask is built (Neal, 2026-11-02), so a Spanish app has a mic in Search but not here.
     */
    val micShown: StateFlow<Boolean> = voice?.status
        ?.map { it.micShown && it.tag?.substringBefore('-') in ASK_VOICE_LANGUAGES }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
        ?: MutableStateFlow(false)

    /**
     * One take of the conversation's mic: the words go into the box after anything already typed, and the question
     * sends itself when the take ends. A take that hears nothing ends the conversation, so the mic is never left open.
     */
    private fun listen() {
        val dictation = dictation ?: return
        if (live.value != null) return
        val typed = input.trimEnd()
        val prefix = if (typed.isEmpty()) "" else "$typed "
        val started = dictation.start(
            onText = { input = prefix + it },
            onFailed = { endConversation() },
        ) { heard ->
            input = prefix + heard
            if (conversing.value && input.isNotBlank()) send() else endConversation()
        }
        if (!started && !dictation.listening) endConversation()
    }

    /** The ask box's speaker: whether replies are read aloud as they arrive. */
    val speakReplies: StateFlow<Boolean> = speakAnswers

    /** Turning it off stops a reply being read now. */
    fun setSpeakReplies(on: Boolean) {
        setSpeakAnswers(on)
        if (!on && !conversing.value && state.value.turns.any { it.voice != VoiceButton.IDLE }) player?.stopVoice()
    }

    /** Replies are spoken: the speaker is on, or a conversation is. */
    private fun speaking() = speakAnswers.value || conversing.value

    // ---- conversation mode ----

    private val conversing = MutableStateFlow(false)

    /**
     * Conversation mode (Neal, 2026-11-02), the ask box's mic: speak, the reply is read aloud as it arrives, then the
     * mic listens again, hands-free until a take hears nothing, Stop, End, or the chat leaves the screen. Not stored.
     */
    val conversation: StateFlow<Boolean> = conversing

    /** Whether the chat offers conversation mode: the ask box has a mic, and there's a voice to answer with. */
    val conversationShown: StateFlow<Boolean> =
        if (player == null) MutableStateFlow(false) else micShown

    /** Waits for the answer's reading to end, then listens again; one at a time. */
    private var nextTake: Job? = null
    /** The turn [runTurn] just settled, for conversation mode to answer and listen after. */
    private var lastSettled: Long? = null
    /** The turn whose answer was read as it streamed, so conversation mode doesn't read it twice. */
    private var streamedTurn: Long? = null
    /** On screen (started); conversation mode ends when it isn't. */
    private var onScreen = false

    fun startConversation() {
        if (!conversationShown.value || conversing.value) return
        conversing.value = true
        if (live.value == null) listen()
    }

    fun endConversation() {
        conversing.value = false
        nextTake?.cancel()
        nextTake = null
        dictation?.takeIf { it.listening }?.cancel()
    }

    /**
     * After a turn in conversation mode: waits for its reply to be read out (or stopped; pausing waits), then listens
     * again. The mic stops the voice before it listens ([VoiceInput.beforeListening]), so it never hears Granger.
     */
    private fun answerThenListen(turnId: Long) {
        val hub = player ?: return
        nextTake?.cancel()
        nextTake = viewModelScope.launch {
            if (streamedTurn == turnId) {
                val key = answerKey(turnId)
                fun onThis(p: PlayerUi) = (p.now as? NowPlaying.Voice)?.takeIf { it.sourceKey == key && !it.ended }
                // The read may take a moment to start; one that never does still lets the conversation go on.
                if (withTimeoutOrNull(READ_START_MS) { hub.ui.first { onThis(it) != null } } != null) {
                    hub.ui.first { onThis(it) == null }
                }
                delay(AFTER_VOICE_MS)
            }
            listenAgain()
        }
    }

    private fun listenAgain() {
        if (conversing.value && onScreen && live.value == null) listen()
    }

    fun send() {
        if (live.value != null) return
        val quote = pendingQuote.value
        val typed = input.trim().take(MAX_QUESTION).trimEnd()
        val question = typed.ifEmpty { if (quote != null) EMPTY_QUOTE_QUESTION else return }
        input = ""
        pendingQuote.value = null
        if (quote != null) savedState[QUOTE_USED] = true
        val newTitle = if (quote != null && question == EMPTY_QUOTE_QUESTION) {
            // Stored as the chat's title, so it stays a literal until a chat can carry its quote instead (L2).
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
        endConversation()
        val running = live.value ?: return
        if (running.stage == Stage.WRITING && running.partial != null) stopRequested = true else job?.cancel()
    }

    /**
     * The Best guess button: opens [turnId]'s guess. A declined turn that may have one gets it written by itself, out
     * of sight (see [runTurn]), so the tap shows a kept guess, or the one still being written, without another model
     * run. Only a turn whose guess was stopped, failed or never came asks the model now. The guess is kept on that turn
     * when it finishes or is stopped; if the app dies first nothing is kept and the button returns. Never for a
     * medical turn.
     */
    fun bestGuess(turnId: Long) {
        shownGuesses.update { it + turnId }
        if (live.value != null) return
        if (state.value.turns.any { it.turn.id == turnId && it.turn.guess.isNotBlank() }) return
        val id = chat.value ?: return
        begin { guess(id, turnId) }
    }

    private suspend fun guess(chatId: Long, turnId: Long) {
        val turns = chats.turns(chatId).first()
        val at = turns.indexOfFirst { it.id == turnId }
        val turn = turns.getOrNull(at)?.takeIf {
            it.outcome == TurnOutcome.DECLINED && it.guess.isBlank() && !it.medical && !isEnglishOnly(it.question)
        } ?: return
        live.value = Live(turnId, null, null, guessing = true, waking = wakingLine())
        var settled = false
        // The guess goes to the model alone: no passages, and no earlier answer or guess, only the question before it.
        pipeline.guess(turn.question, turns.getOrNull(at - 1)?.question) { stopRequested }.flowOn(cpu).collect { event ->
            when (event) {
                is ResearchEvent.Progress -> live.update { it?.copy(stage = event.stage) }
                is ResearchEvent.Partial -> live.update { it?.copy(partial = event.text) }
                is ResearchEvent.Done -> {
                    val outcome = event.outcome
                    if (outcome is Outcome.Guessed && !settled) {
                        settled = true
                        withContext(NonCancellable) { chats.setGuess(turnId, outcome.text) }
                    } else if (outcome is Outcome.Failed) {
                        outcome.cause?.let { runCatching { android.util.Log.w("Granger", "Research guess failed", it) } }
                        // Only the model can fail a guess (it does no search); say so, without storing it.
                        guessFailed.value = turnId
                    }
                }
                // run() events; guess() doesn't emit them.
                ResearchEvent.Medical, is ResearchEvent.Reaction, is ResearchEvent.LookingIn, is ResearchEvent.Reading -> Unit
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

    /** Resolves citations; every passage opens through it. */
    private val opener = CitationOpener(sessions, library, bookshelf, media)

    /** Opens cited passage [n] (1-based) of turn [turnId], as [open] does. */
    suspend fun open(turnId: Long, n: Int): CitationTarget {
        val turn = state.value.turns.firstOrNull { it.turn.id == turnId }?.turn ?: return CitationTarget.Gone
        val passage = turn.shown.getOrNull(n - 1) ?: return CitationTarget.Gone
        return open(passage, turn.answer, n, ResearchLanguage.of(turn.question))
    }

    /**
     * Opens [passage], cited as `[n]` in [answer], at the sentence the answer used where it can; [CitationTarget.Gone]
     * when it's gone, or can't be opened (a storage error is logged, not thrown into the screen's tap handler).
     * [languages] are the question's, for picking the quote.
     */
    suspend fun open(passage: Passage, answer: String = "", n: Int = 0, languages: List<ResearchLanguage> = Keywords.ENGLISH): CitationTarget = try {
        opener.open(passage, answer, n, languages)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { android.util.Log.w("Granger", "Research citation couldn't be opened", e) }
        CitationTarget.Gone
    }

    /** Whether cited media can be played here (a source card shows Play). */
    val canPlay: Boolean get() = player != null

    /**
     * Plays cited media [n] of turn [turnId]: an album from its cited song (else its first), an audiobook from its cited
     * chapter's start (else where it was left). Stays in Research; the mini-player shows. False when it's gone.
     */
    suspend fun play(turnId: Long, n: Int): Boolean {
        val hub = player ?: return false
        val passage = state.value.turns.firstOrNull { it.turn.id == turnId }?.turn?.shown?.getOrNull(n - 1) ?: return false
        if (passage.kind != SourceKind.MEDIA) return false
        return try {
            when (val target = open(passage, n = n)) {
                is CitationTarget.Album -> {
                    val work = media.work(ALBUM_SOURCE + target.albumKey) ?: return false
                    if (work.partIds.isEmpty()) return false
                    val start = target.trackId?.let(work.partIds::indexOf)?.takeIf { it >= 0 } ?: 0
                    hub.playTracks(work.partIds, start, shuffle = false, PlaySource(ALBUM_SOURCE + target.albumKey, work.title))
                    true
                }
                is CitationTarget.Audiobook -> {
                    val chapter = target.chapter?.let { media.chapter(target.audiobookId, it) }
                    hub.playAudiobook(target.audiobookId, chapter?.let { AudiobookShelf.at(target.audiobookId, it, now()) })
                    true
                }
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { android.util.Log.w("Granger", "Research media couldn't be played", e) }
            false
        }
    }

    /** Deletes this chat (stopping a running turn) and leaves the view empty. */
    fun deleteChat() {
        val id = chat.value ?: return
        leaveChat()
        appScope.launch { logErrors { chats.delete(id) } }
    }

    /** Deletes every chat (Plan U3b Decision 7), this one too: a running turn stops and Ask shows a fresh chat. */
    fun deleteAllChats() {
        leaveChat()
        appScope.launch { logErrors { chats.deleteAll() } }
    }

    /** Stops a running turn and empties the view, for a chat about to be deleted. */
    private fun leaveChat() {
        job?.cancel()
        chat.value = null
        savedState[CHAT_ID] = null
        title.value = ""
    }

    /** Why Ask is off on this phone (the model failed its self-check), or null. */
    val checkFailure = models.checkFailure

    /** Try again after a failed self-check: the next question tests the model afresh. */
    fun retryCheck() {
        appScope.launch { logErrors { models.retryCheck() } }
    }

    /** Set while this chat is on screen: the models stay loaded (Neal, 2026-11-02); null while it isn't. */
    private var keepOpen: (() -> Unit)? = null

    /** The chat is on screen (started): the models stay loaded until [hidden] and five minutes after. */
    fun shown() {
        onScreen = true
        if (keepOpen == null) keepOpen = models.keepWhileOpen()
    }

    /** The chat left the screen (a reader on top, another tool, the app hidden): the quiet spell may start. */
    fun hidden() {
        onScreen = false
        endConversation()
        keepOpen?.invoke()
        keepOpen = null
    }

    override fun onCleared() {
        job?.cancel()
        hidden()
        models.releaseSoon()
    }

    /**
     * Runs [work] as the one running turn; nothing else starts until it ends. The turn holds the models, so a release
     * asked for meanwhile (the app going to the background) waits for its end rather than reloading between stages;
     * after it, they stay while an Ask screen is open ([shown]) and go a quiet spell after the last one closes.
     */
    private fun begin(work: suspend () -> Unit) {
        stopRequested = false
        guessFailed.value = null
        lastSettled = null
        nextTake?.cancel()
        live.value = Live(null, null, null)
        job = viewModelScope.launch {
            val letGo = models.hold()
            try {
                logErrors { work() }
            } finally {
                live.value = null
                // Asked while still held, so a background release waiting on the hold isn't replaced by the timer.
                models.releaseSoon()
                letGo()
                lastSettled?.takeIf { conversing.value }?.let(::answerThenListen)
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
        val request = ResearchRequest(ask.question, ask.quote, prior, before.lastOrNull()?.question)
        val kept = listOfNotNull(ask.quote)
        var turnId: Long? = null
        var settled = false
        // The pipeline's verdict once known (it can be ahead of the stored turn); a turn cut short keeps it.
        var medical = false
        // The reaction placed it as a health question, before the pipeline's verdict: "Looking in…" stays plain.
        var healthReaction = false
        // Neal's feel test (Plan R5): a decline goes straight on to the model's best guess. Since 2026-11-02 it is written
        // out of sight and opened with the Best guess button, so "Not in your library" stands until the user asks.
        // Not for a medical turn, a question about a selection, or a stopped one.
        var guessNext = false
        try {
            // Not cancellable, so a turn that was stored is always known here and can be settled.
            withContext(NonCancellable) {
                turnId = chats.startTurn(chatId, ask.question, ask.quote)
                spokenLead = ""
                answerSpoken = false
                spokenAnswer = ""
                live.value = Live(turnId, null, null, waking = wakingLine())
            }
            if (!spanishAsk && ResearchLanguage.main(ask.question) == ResearchLanguage.ES) {
                withContext(NonCancellable) {
                    chats.finishTurn(checkNotNull(turnId), TurnOutcome.DECLINED, "", false, emptyList(), kept, emptyList())
                    settled = true
                    lastSettled = turnId
                    sayOutcome(chatId, checkNotNull(turnId), ask.question, Outcome.Declined(false, emptyList()))
                }
                return
            }
            pipeline.run(request) { stopRequested }.flowOn(cpu).collect { event ->
                when (event) {
                    is ResearchEvent.Reaction -> {
                        healthReaction = event.topic == ReactionTopic.MEDICAL
                        if (owlSpeaks()) {
                            val line = OwlLines.pick(event.topic, saidLines).also { saidLines += it }
                            live.update { it?.copy(reaction = line) }
                            sayLead(turnId, ask.question, line)
                        }
                    }
                    is ResearchEvent.Reading -> if (owlSpeaks()) {
                        val line = OwlLines.reading(event.passages)
                        live.update { it?.copy(reading = line) }
                        sayLead(turnId, ask.question, line)
                    }
                    is ResearchEvent.LookingIn -> if (owlSpeaks()) {
                        val line = OwlLines.lookingIn(event.titles, medical = healthReaction || medical)
                        live.update { it?.copy(lookingIn = line) }
                        sayLead(turnId, ask.question, line.replace(" · ", ", "))
                    }
                    is ResearchEvent.Progress -> {
                        live.update { it?.copy(stage = event.stage) }
                        // The owl's waking line shows while the model loads; it's said then too.
                        if (event.stage == Stage.LOADING_MODEL) live.value?.waking?.let { sayLead(turnId, ask.question, it) }
                    }
                    is ResearchEvent.Partial -> {
                        live.update { it?.copy(partial = event.text) }
                        speakPartial(turnId, ask.question, event.text)
                    }
                    ResearchEvent.Medical -> {
                        medical = true
                        live.update { it?.copy(medical = true) }
                        sayLead(turnId, ask.question, AnswerText.MEDICAL)
                    }
                    is ResearchEvent.Done -> withContext(NonCancellable) {
                        (event.outcome as? Outcome.Failed)?.cause?.let { runCatching { android.util.Log.w("Granger", "Research turn failed", it) } }
                        settle(checkNotNull(turnId), event.outcome, kept)
                        settled = true
                        lastSettled = turnId
                        sayOutcome(chatId, checkNotNull(turnId), ask.question, event.outcome)
                        // Not in conversation mode: the guess would hold the model while the mic waits for the next question.
                        guessNext = event.outcome.let { it is Outcome.Declined && !it.medical } && ask.quote == null && !stopRequested &&
                            !conversing.value
                    }
                }
            }
        } catch (e: CancellationException) {
            val id = turnId
            if (id != null && !settled) {
                appScope.launch {
                    logErrors { chats.finishTurn(id, TurnOutcome.INTERRUPTED, "", medical, emptyList(), kept, emptyList()) }
                }
            }
            throw e
        } catch (e: Exception) {
            // The pipeline reports model and search errors as outcomes, so this is storage or a bug, not the model:
            // "Couldn't search your library. Try again", not "The model needs repair".
            runCatching { android.util.Log.w("Granger", "Research turn failed", e) }
            val id = turnId
            if (id != null && !settled) {
                // The error may be the database's own; the turn then stays PENDING until the next launch interrupts it.
                withContext(NonCancellable) {
                    logErrors { chats.finishTurn(id, TurnOutcome.SEARCH_FAILED, "", medical, emptyList(), kept, emptyList()) }
                }
            }
        } finally {
            // However the turn ended, the read of it ends too: what was spoken stays, nothing more is waited for.
            streaming?.complete()
            streaming = null
        }
        if (guessNext) guess(chatId, checkNotNull(turnId))
    }

    // ---- read aloud (Plan V2) ----

    /** The reply being read as it arrives, while replies are spoken; one turn at a time. */
    private var streaming: StreamingTextReadSource? = null
    /** What the owl has said before the answer (its reaction, where it's looking, what it's reading), as paragraphs. */
    private var spokenLead = ""
    /** Whether the answer itself has begun to be read, so the outcome isn't read twice. */
    private var answerSpoken = false
    /** The answer's spoken text so far, for a line said after it. */
    private var spokenAnswer = ""

    /** This turn's spoken reply, started on its first line while replies are spoken; null while they aren't. */
    private fun stream(turnId: Long?, question: String): StreamingTextReadSource? {
        val hub = player ?: return null
        val id = turnId ?: return null
        return streaming ?: run {
            if (!speaking()) return null
            StreamingTextReadSource(answerKey(id), question, remembered = false).also {
                streaming = it
                streamedTurn = id
                hub.readAloud(it)
            }
        }
    }

    /** One of the owl's lines before the answer: said in order, each as soon as it shows. */
    private fun sayLead(turnId: Long?, question: String, line: String) {
        if (answerSpoken || line.isBlank()) return
        val source = stream(turnId, question) ?: return
        spokenLead = if (spokenLead.isEmpty()) line else "$spokenLead\n\n$line"
        // A trailing break: every line is whole, so it is read now rather than waiting for more.
        source.append("$spokenLead\n\n")
    }

    /** A new piece of the answer, read after the owl's lines. */
    private fun speakPartial(turnId: Long?, question: String, text: String) {
        val source = stream(turnId, question) ?: return
        answerSpoken = true
        spokenAnswer = AnswerText.spoken(text)
        // The spoken form is trimmed; the trailing space says whether the last sentence may still be growing.
        val trailing = if (text.endsWith(" ") || text.endsWith("\n")) " " else ""
        source.append(withLead(spokenAnswer) + trailing)
    }

    private fun withLead(text: String) = listOf(spokenLead, text).filter { it.isNotBlank() }.joinToString("\n\n")

    /**
     * The settled turn, while replies are spoken: an answer that didn't stream is read whole, and a decline or failure
     * is said in the screen's words ([outcomeLine]), after the owl's lines.
     */
    private suspend fun sayOutcome(chatId: Long, turnId: Long, question: String, outcome: Outcome) {
        if (!speaking()) return
        val text = when (outcome) {
            is Outcome.Answered -> if (answerSpoken) return else AnswerText.spoken(outcome.text)
            else -> {
                val ui = chats.turns(chatId).first().firstOrNull { it.id == turnId }?.toUi(null, null, emptySet(), null) ?: return
                outcomeLine(ui) ?: return
            }
        }
        val source = stream(turnId, question) ?: return
        // After an answer that streamed and then didn't stand (unverified), the line follows what was read.
        source.append(withLead(listOf(if (answerSpoken) spokenAnswer else "", text).filter { it.isNotBlank() }.joinToString("\n\n")))
    }

    /** Reads [turnId]'s answer (or its kept guess) aloud through the player, from the start. */
    fun readAnswer(turnId: Long) {
        val hub = player ?: return
        val turn = state.value.turns.firstOrNull { it.turn.id == turnId }?.turn ?: return
        val text = turn.answer.ifBlank { turn.guess }
        if (text.isBlank()) return
        hub.readAloud(TextReadSource(answerKey(turnId), turn.question, AnswerText.spoken(text, turn.medical), remembered = false))
    }

    /** The speaker button while its answer is read: pauses the voice, or resumes it at its sentence. */
    fun pauseResumeReading() { player?.playPause() }

    fun stopReading() { player?.stopVoice() }

    /** Declined and failed turns keep only the quote. */
    private suspend fun settle(turnId: Long, outcome: Outcome, kept: List<Passage>) {
        when (outcome) {
            is Outcome.Answered -> chats.finishTurn(
                turnId, TurnOutcome.ANSWERED, outcome.text, outcome.medical, outcome.keywords, outcome.passages, outcome.candidates,
            )
            is Outcome.Unverified -> chats.finishTurn(
                turnId, TurnOutcome.UNVERIFIED, outcome.text, outcome.medical, outcome.keywords, outcome.passages, outcome.candidates,
            )
            is Outcome.Declined ->
                chats.finishTurn(turnId, TurnOutcome.DECLINED, "", outcome.medical, outcome.keywords, kept, emptyList())
            is Outcome.Failed -> {
                val kind = if (outcome.reason == FailReason.SEARCH_FAILED) TurnOutcome.SEARCH_FAILED else TurnOutcome.FAILED
                chats.finishTurn(turnId, kind, "", outcome.medical, outcome.keywords, kept, emptyList())
            }
            // Only ResearchPipeline.guess produces this, and bestGuess keeps it; a turn run() settles this way failed.
            is Outcome.Guessed -> chats.finishTurn(turnId, TurnOutcome.FAILED, "", false, emptyList(), kept, emptyList())
        }
    }

    private fun ChatTurn.toUi(live: Live?, guessFailedId: Long?, shown: Set<Long>, reading: Reading?): TurnUi {
        val running = live?.takeIf { it.turnId == id }
        val hasGuess = guess.isNotBlank() || running?.guessing == true
        val englishOnly = outcome == TurnOutcome.DECLINED && isEnglishOnly(question)
        return TurnUi(
            voice = when {
                reading?.turnId != id -> VoiceButton.IDLE
                reading.playing -> VoiceButton.READING
                else -> VoiceButton.PAUSED
            },
            turn = this,
            partial = running?.partial,
            stage = running?.stage,
            reaction = running?.reaction,
            lookingIn = running?.lookingIn,
            reading = running?.reading,
            waking = running?.waking,
            medical = medical || running?.medical == true,
            guessing = running?.guessing == true,
            offersGuess = outcome == TurnOutcome.DECLINED && !medical && !englishOnly && !(id in shown && hasGuess) &&
                (hasGuess || live == null),
            // Opened and there is something to show; a failed or empty guess brings the button back.
            guessShown = id in shown && hasGuess,
            // The pipeline's own rule (ResearchPipeline: PersonalMedical with the medical flag, no selection).
            personalMedical = outcome == TurnOutcome.DECLINED && medical && quote() == null &&
                PersonalMedical.matches(question, medicalSignal = true),
            englishOnly = englishOnly,
            guessFailed = guessFailedId == id && live == null,
        )
    }

    /** A question Ask declines unasked because it's in Spanish and Spanish Ask is off. */
    private fun isEnglishOnly(question: String) = !spanishAsk && ResearchLanguage.main(question) == ResearchLanguage.ES

    /** The selection this turn was asked about, kept as its first shown passage. */
    private fun ChatTurn.quote(): Passage? = shown.firstOrNull()?.takeIf { it.kind == SourceKind.SELECTION }

    companion object {
        /** The app languages Ask answers in, so the ask box's mic shows (Plan A1): English until Spanish Ask is built. */
        val ASK_VOICE_LANGUAGES = setOf("en")

        /** Conversation mode: how long a reply's reading may take to start, and the pause after it before listening. */
        const val READ_START_MS = 5_000L
        const val AFTER_VOICE_MS = 300L

        /** The read-source key of a spoken answer (Plan V2), and the turn it names. */
        fun answerKey(turnId: Long) = "text:answer:$turnId"
        fun answerTurnId(key: String): Long? = key.removePrefix("text:answer:").takeIf { it != key }?.toLongOrNull()

        // Not "chatId": the handle also holds the route's arguments, and ResearchRoute's chatId is 0 for a new chat, which
        // read as a saved chat 0 that was never created (every new chat reopened it, 22–27 Oct).
        const val CHAT_ID = "research.chatId"
        const val QUOTE_USED = "research.quoteUsed"
        const val RECENT_CHATS = 10
        /** Longer questions are cut, so a pasted wall of text can't overflow the prompt. */
        const val MAX_QUESTION = 1_000
        const val TITLE = 60
        const val QUOTE_TITLE = 40
        private val RETRYABLE = setOf(TurnOutcome.INTERRUPTED, TurnOutcome.FAILED, TurnOutcome.SEARCH_FAILED)
    }
}

/** A media card's sourceId for an album: "album:<albumKey>" (an audiobook's is its id). */
internal const val ALBUM_SOURCE = "album:"

/**
 * A source card's second line: the author (or the pack, for an article without one) and the heading; for an album or
 * audiobook, the artist or author (else "Your music" / "Your audiobooks"), [album] or [audiobook] ("Album", "Audiobook"),
 * and the cited song or chapter.
 */
internal fun sourceByline(passage: Passage, album: String, audiobook: String): String {
    val who = passage.meta.author.ifBlank { passage.packTitle }
    val what = if (passage.kind != SourceKind.MEDIA) passage.heading
    else if (passage.sourceId.startsWith(ALBUM_SOURCE)) album else audiobook
    // A card focused on a song or chapter names it too.
    val part = if (passage.kind == SourceKind.MEDIA) passage.heading else ""
    return listOf(who, what, part).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * What "Search instead" searches for: the first rewrite keyword, else the question. The library's Search
 * matches whole phrases, so every keyword joined ("world cup 2031 football soccer") found nothing.
 */
internal fun searchInsteadQuery(keywords: List<String>, question: String): String =
    keywords.firstOrNull { it.isNotBlank() }?.trim() ?: question

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
