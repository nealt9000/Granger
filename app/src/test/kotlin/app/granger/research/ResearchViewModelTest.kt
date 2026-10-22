package app.granger.research

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.granger.BookTarget
import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.LlmEngineFactory
import app.granger.core.ai.LlmSize
import app.granger.core.ai.ModelManager
import app.granger.core.ai.ResearchReadiness
import app.granger.core.ai.TextEmbedderFactory
import app.granger.core.books.TextQuote
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import app.granger.core.library.TurnOutcome
import app.granger.core.research.Passage
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import app.granger.music.FakeMediaLibrary
import app.granger.music.song
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ResearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val chats = FakeChats()
    private val finder = FakeFinder()
    private val small = ScriptedGenerator()
    private val models = FakeResearchModels(small)
    private val sessions = FakeReadingSessions()
    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val mediaLibrary = FakeMediaLibrary()
    private val installed = MutableStateFlow(InstalledModels(small = File("small"), large = File("large")))

    private val quote = Passage(
        SourceKind.SELECTION, "pack1", "Article 1", location = "A/Art1",
        text = "Chlorophyll in the leaves absorbs light so the plant can make sugar from water and air.",
    )

    private fun TestScope.vm(
        chatId: Long? = null,
        quote: Passage? = null,
        manager: ModelManager = manager(LlmEngineFactory { _, _ -> error("the view model doesn't load models itself") }),
        savedState: SavedStateHandle = SavedStateHandle(),
        cpu: CoroutineDispatcher = main.dispatcher,
        sessions: ReadingSessions = this@ResearchViewModelTest.sessions,
        player: app.granger.core.player.PlayerHub? = null,
        speakAnswers: Boolean = false,
    ): ResearchViewModel {
        return ResearchViewModel(
            chatId, quote, chats, ResearchPipeline(finder, models), manager, sessions, library, bookshelf,
            MediaIndex(mediaLibrary, backgroundScope, main.dispatcher), backgroundScope, savedState, cpu,
            player = player, speakAnswers = { speakAnswers },
        )
    }

    // ---- read aloud (Plan V2) ----

    @Test fun readAnswerStartsTheVoiceOnTheTurnsSpokenText() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = vm(player = hub)
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(hub.calls).isEmpty()                                   // the setting is off: nothing read as it streamed
        vm.readAnswer(turn.id)
        assertThat(hub.calls).containsExactly("readAloud:text:answer:${turn.id}@null")
        val source = hub.lastSource!!
        assertThat(source.title).isEqualTo("How do plants use light?")
        assertThat(source.utterances(null, 10).map { it.text }).containsExactly("Plants turn light into sugar.")   // no [1]
        // While it is read, the turn's button follows the player.
        hub.ui.value = app.granger.player.voiceUi(title = turn.question).let {
            it.copy(now = (it.now as app.granger.core.player.NowPlaying.Voice).copy(sourceKey = "text:answer:${turn.id}"))
        }
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().voice).isEqualTo(app.granger.player.VoiceButton.READING)
        vm.pauseResumeReading()
        vm.stopReading()
        assertThat(hub.calls.takeLast(2)).containsExactly("playPause", "stopVoice").inOrder()
    }

    @Test fun withTheSettingOnANewAnswerIsReadAsItStreamsAndEndsWithTheTurn() = runTest(main.dispatcher) {
        val hub = app.granger.player.FakePlayerHub()
        val vm = vm(player = hub, speakAnswers = true)
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(hub.calls).containsExactly("readAloud:text:answer:${turn.id}@null")
        val source = hub.lastSource as app.granger.core.speech.StreamingTextReadSource
        assertThat(source.isComplete).isTrue()
        assertThat(source.utterances(null, 10).map { it.text }).containsExactly("Plants turn light into sugar.")
        // A second question reads its own answer afresh.
        vm.ask("And at night?")
        testScheduler.advanceUntilIdle()
        assertThat(hub.calls).hasSize(2)
        assertThat(hub.lastSource!!.key).isEqualTo("text:answer:${chats.turnList.value.last().id}")
    }

    private fun TestScope.manager(llms: LlmEngineFactory, tier: DeviceTier = DeviceTier.THINK_DEEPER) = ModelManager(
        tier, installed, llms, TextEmbedderFactory { error("no embedder") }, backgroundScope,
        useEmbedder = false, io = StandardTestDispatcher(testScheduler),
    )

    private class ClosingLlm : LlmEngine {
        var closed = false
        override fun load() {}
        override fun generate(prompt: String, maxTokens: Int): Flow<String> = emptyFlow()
        override fun close() { closed = true }
    }

    private fun ResearchViewModel.ask(question: String) {
        input = question
        send()
    }

    private fun ResearchViewModel.clear() = ViewModelStore().also { it.put("vm", this) }.clear()

    @Test fun searchInsteadUsesTheFirstKeyword() {
        assertThat(searchInsteadQuery(listOf("world cup", "2031", "football"), "Who won the 2031 World Cup?")).isEqualTo("world cup")
        assertThat(searchInsteadQuery(listOf(" ", ""), "Who won the 2031 World Cup?")).isEqualTo("Who won the 2031 World Cup?")
        assertThat(searchInsteadQuery(emptyList(), "Why is the sky green?")).isEqualTo("Why is the sky green?")
    }

    @Test fun firstSendCreatesTheChatTitledFromTheQuestion() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("  How do plants make their food from light, water and air in their leaves?  ")
        assertThat(vm.input).isEmpty()
        testScheduler.advanceUntilIdle()
        val chat = chats.chats.value.single()
        assertThat(chat.title).isEqualTo("How do plants make their food from light, water and air in")
        assertThat(vm.state.value.chatId).isEqualTo(chat.id)
        assertThat(vm.state.value.title).isEqualTo(chat.title)
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun answeredTurnIsStoredWithItsPassages() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.ANSWERED)
        assertThat(turn.answer).isEqualTo("Plants turn light into sugar [1].")
        assertThat(turn.keywords).containsExactly("photosynthesis", "light")
        assertThat(turn.shown).hasSize(4)
        assertThat(turn.candidates).hasSize(4)
        assertThat(vm.state.value.turns.single().turn).isEqualTo(turn)
        assertThat(vm.state.value.turns.single().partial).isNull()
    }

    @Test fun followUpPassesThePriorTurn() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        vm.ask("And at night?")
        testScheduler.advanceUntilIdle()
        assertThat(small.prompts).hasSize(4)
        assertThat(small.prompts[2]).contains("Previous question: How do plants use light?")
        assertThat(small.prompts[3]).contains("Earlier in this conversation")
        assertThat(small.prompts[3]).contains("Plants turn light into sugar")
        assertThat(chats.turnList.value.map { it.question }).containsExactly("How do plants use light?", "And at night?").inOrder()
    }

    @Test fun blankSendWithQuoteAsksTheDefaultQuestion() = runTest(main.dispatcher) {
        val vm = vm(quote = quote)
        vm.ask("   ")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.question).isEqualTo("Explain this passage and how it connects to the rest of my library.")
        assertThat(chats.chats.value.single().title).isEqualTo("About: Chlorophyll in the leaves absorbs light")
        assertThat(turn.shown.first()).isEqualTo(quote)
    }

    @Test fun blankSendWithoutQuoteDoesNothing() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("  ")
        testScheduler.advanceUntilIdle()
        assertThat(chats.chats.value).isEmpty()
        assertThat(small.prompts).isEmpty()
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun quoteOnlyOnTheFirstTurn() = runTest(main.dispatcher) {
        val vm = vm(quote = quote)
        assertThat(vm.state.value.quote).isEqualTo(quote)
        vm.ask("What absorbs light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.quote).isNull()
        vm.ask("And what else?")
        testScheduler.advanceUntilIdle()
        val (first, second) = chats.turnList.value
        assertThat(first.shown.first()).isEqualTo(quote)
        assertThat(second.shown.none { it.kind == SourceKind.SELECTION }).isTrue()
        assertThat(small.answerPrompts[1]).doesNotContain("Chlorophyll in the leaves")
    }

    @Test fun processDeathRestoresTheNewChatWithoutItsQuote() = runTest(main.dispatcher) {
        val savedState = SavedStateHandle()
        val first = vm(quote = quote, savedState = savedState)
        first.ask("What absorbs light?")
        testScheduler.advanceUntilIdle()
        val chatId = chats.chats.value.single().id
        // The process dies; the route still has no chat id and the reader's quote, and the handle comes back.
        val restored = vm(quote = quote, savedState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        testScheduler.advanceUntilIdle()
        assertThat(restored.state.value.chatId).isEqualTo(chatId)
        assertThat(restored.state.value.title).isEqualTo("What absorbs light?")
        assertThat(restored.state.value.turns).hasSize(1)
        assertThat(restored.state.value.quote).isNull()
    }

    @Test fun aDeletedChatStaysGoneAfterProcessDeath() = runTest(main.dispatcher) {
        val chatId = chats.create("How do plants use light?")
        val savedState = SavedStateHandle()
        vm(chatId = chatId, savedState = savedState).deleteChat()
        testScheduler.advanceUntilIdle()
        val restored = vm(chatId = chatId, savedState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) }))
        testScheduler.advanceUntilIdle()
        assertThat(restored.state.value.chatId).isNull()
    }

    @Test fun stopKeepsAValidlyCitedPartial() = runTest(main.dispatcher) {
        small.answer = listOf("Plants use light [1].", " And", " more [9].")
        small.gateAt = 1
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val live = vm.state.value.turns.single()
        assertThat(vm.state.value.running).isTrue()
        assertThat(live.partial).isEqualTo("Plants use light [1].")
        assertThat(live.stage).isEqualTo(Stage.WRITING)
        vm.stop()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.ANSWERED)
        assertThat(turn.answer).isEqualTo("Plants use light [1]. And")
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun medicalBannerShowsWhileTheAnswerStreams() = runTest(main.dispatcher) {
        small.rewrite = "keywords: photosynthesis, light\nmedical: yes"
        small.answer = listOf("Cool the burn under running water [1].", " Then")
        small.gateAt = 1
        val vm = vm()
        vm.ask("How do I treat a burn?")
        testScheduler.advanceUntilIdle()
        val live = vm.state.value.turns.single()
        assertThat(live.turn.outcome).isEqualTo(TurnOutcome.PENDING)
        assertThat(live.turn.medical).isFalse()
        assertThat(live.medical).isTrue()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().medical).isTrue()
    }

    @Test fun notMedicalHasNoBanner() = runTest(main.dispatcher) {
        small.answer = listOf("Plants use light [1].", " And")
        small.gateAt = 1
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().medical).isFalse()
    }

    /** A question the library can't answer: the finder has nothing, so the turn is declined. */
    private suspend fun TestScope.declined(vm: ResearchViewModel, question: String = "Why is the sky green?"): Long {
        finder.passages = emptyList()
        vm.ask(question)
        testScheduler.advanceUntilIdle()
        return chats.turnList.value.last().id
    }

    @Test fun declineOffersBestGuess() = runTest(main.dispatcher) {
        val vm = vm()
        declined(vm)
        val ui = vm.state.value.turns.single()
        assertThat(ui.turn.outcome).isEqualTo(TurnOutcome.DECLINED)
        assertThat(ui.offersGuess).isTrue()
        assertThat(ui.guessing).isFalse()
    }

    @Test fun answeredTurnOffersNoBestGuess() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().offersGuess).isFalse()
    }

    @Test fun medicalDeclineOffersNoBestGuess() = runTest(main.dispatcher) {
        small.rewrite = "keywords: burn\nmedical: yes"
        val vm = vm()
        declined(vm, "How do I treat a burn?")
        val ui = vm.state.value.turns.single()
        assertThat(ui.turn.outcome).isEqualTo(TurnOutcome.DECLINED)
        assertThat(ui.offersGuess).isFalse()
    }

    @Test fun stoppedMedicalTurnOffersNoBestGuess() = runTest(main.dispatcher) {
        small.rewrite = "keywords: photosynthesis, light\nmedical: yes"
        small.answer = listOf("NOT IN", " LIBRARY")
        small.gateAt = 1
        val vm = vm()
        vm.ask("How do I treat a burn?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().medical).isTrue()
        vm.stop()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val ui = vm.state.value.turns.single()
        assertThat(ui.turn.outcome).isEqualTo(TurnOutcome.DECLINED)
        assertThat(ui.offersGuess).isFalse()
        vm.bestGuess(ui.turn.id)
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().guess).isEmpty()
    }

    @Test fun bestGuessStoresTextAndHidesTheButton() = runTest(main.dispatcher) {
        models.large = ScriptedGenerator(listOf("Probably ", "Rayleigh scattering."))
        val vm = vm()
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.DECLINED)
        assertThat(turn.guess).isEqualTo("Probably Rayleigh scattering.")
        assertThat(turn.guessLarge).isTrue()
        assertThat(turn.guessFellBack).isFalse()
        assertThat(vm.state.value.turns.single().offersGuess).isFalse()
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun guessShowsNoSourceCards() = runTest(main.dispatcher) {
        small.answer = listOf("Probably ", "Rayleigh scattering.")
        small.gateAt = 1
        val vm = vm()
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        val live = vm.state.value.turns.single()
        assertThat(live.guessing).isTrue()
        assertThat(live.partial).isEqualTo("Probably ")
        assertThat(live.turn.shown).isEmpty()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().turn.shown).isEmpty()
        assertThat(vm.state.value.turns.single().turn.candidates).isEmpty()
    }

    @Test fun largeGuessShowsTheWaitNote() = runTest(main.dispatcher) {
        val large = ScriptedGenerator(listOf("Large ", "guess."))
        large.gateAt = 1
        models.large = large
        val vm = vm()
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        val live = vm.state.value.turns.single()
        assertThat(live.guessing).isTrue()
        assertThat(live.guessLarge).isTrue()
        large.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.guess).isEqualTo("Large guess.")
        assertThat(turn.guessLarge).isTrue()
        assertThat(small.answerPrompts).isEmpty()
    }

    @Test fun phoneBelowThinkDeeperTierGuessesWithSmall() = runTest(main.dispatcher) {
        val large = ScriptedGenerator(listOf("Large guess."))
        models.large = large
        small.answer = listOf("Small guess.")
        val vm = vm(manager = manager(LlmEngineFactory { _, _ -> error("no engine") }, DeviceTier.ASK))
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.guess).isEqualTo("Small guess.")
        assertThat(turn.guessLarge).isFalse()
        assertThat(turn.guessFellBack).isFalse()
        assertThat(large.prompts).isEmpty()
    }

    @Test fun guessFallbackShowsTheNote() = runTest(main.dispatcher) {
        small.answer = listOf("Small ", "guess.")
        small.gateAt = 1
        val vm = vm()
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().guessFellBack).isTrue()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.guess).isEqualTo("Small guess.")
        assertThat(turn.guessLarge).isFalse()
        assertThat(turn.guessFellBack).isTrue()
        assertThat(vm.state.value.turns.single().guessFellBack).isTrue()
    }

    @Test fun stopDuringGuessKeepsTheTextSoFar() = runTest(main.dispatcher) {
        small.answer = listOf("Maybe", " plants", " more")
        small.gateAt = 1
        val vm = vm()
        val id = declined(vm)
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().partial).isEqualTo("Maybe")
        vm.stop()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().guess).isEqualTo("Maybe plants")
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun guessInterruptedByProcessDeathOffersTheButtonAgain() = runTest(main.dispatcher) {
        val chatId = chats.create("Why is the sky green?")
        val turnId = chats.startTurn(chatId, "Why is the sky green?", askedDeeper = false, quote = null)
        chats.finishTurn(turnId, TurnOutcome.DECLINED, "", false, false, emptyList(), emptyList(), emptyList())
        val vm = vm(chatId = chatId)
        testScheduler.advanceUntilIdle()
        val ui = vm.state.value.turns.single()
        assertThat(ui.offersGuess).isTrue()
        assertThat(ui.guessing).isFalse()
        assertThat(ui.partial).isNull()
        assertThat(ui.stage).isNull()
        assertThat(ui.turn.guess).isEmpty()
    }

    @Test fun followUpAfterGuessDoesNotPassGuessAsPriorAnswer() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val id = declined(vm)
        small.answer = listOf("Sky guess text.")
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.last().guess).isEqualTo("Sky guess text.")
        finder.passages = (1..10).map(::article)
        small.answer = listOf("Plants rest [1].")
        vm.ask("And at night?")
        testScheduler.advanceUntilIdle()
        val prompt = small.prompts.last()
        assertThat(prompt).contains("Plants turn light into sugar")
        assertThat(prompt).doesNotContain("Sky guess text.")
    }

    @Test fun theModelIsReleasedAQuietSpellAfterEachTurn() = runTest(main.dispatcher) {
        val llm = ClosingLlm()
        val manager = manager(LlmEngineFactory { _, _ -> llm })
        manager.engine(LlmSize.SMALL) // as the turn's pipeline would
        val vm = vm(manager = manager)
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.running).isFalse()
        testScheduler.advanceTimeBy(119_000)
        testScheduler.runCurrent()
        assertThat(llm.closed).isFalse()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertThat(llm.closed).isTrue()
    }

    @Test fun aBackgroundReleaseMidTurnWaitsForTheTurnsEnd() = runTest(main.dispatcher) {
        val llm = ClosingLlm()
        val manager = manager(LlmEngineFactory { _, _ -> llm })
        manager.engine(LlmSize.SMALL)
        small.answer = listOf("Plants use light [1].", " And")
        small.gateAt = 1
        val vm = vm(manager = manager)
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        manager.releaseNow()
        testScheduler.runCurrent()
        assertThat(llm.closed).isFalse()
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
        assertThat(vm.state.value.running).isFalse()
        assertThat(llm.closed).isTrue()
    }

    @Test fun thePipelineRunsOffTheMainThread() = runTest(main.dispatcher) {
        val cpu = StandardTestDispatcher(testScheduler, "cpu")
        val vm = vm(cpu = cpu)
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(finder.ranOn).isSameInstanceAs(cpu)
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.ANSWERED)
    }

    @Test fun stopBeforeWritingInterruptsTheTurn() = runTest(main.dispatcher) {
        finder.gate = CompletableDeferred()
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().stage).isEqualTo(Stage.SEARCHING)
        vm.stop()
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.INTERRUPTED)
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun stopBeforeTheFirstChunkInterruptsTheTurn() = runTest(main.dispatcher) {
        small.gateAt = 0 // the model is still reading the prompt
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val live = vm.state.value.turns.single()
        assertThat(live.stage).isEqualTo(Stage.WRITING)
        assertThat(live.partial).isNull()
        vm.stop()
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.INTERRUPTED)
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun leavingMidAnswerMarksTheTurnInterrupted() = runTest(main.dispatcher) {
        small.answer = listOf("Plants use light [1].", " And")
        small.gateAt = 1
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.PENDING)
        vm.clear()
        testScheduler.advanceUntilIdle()
        // The INTERRUPTED write runs on the app scope (backgroundScope here), which advanceUntilIdle doesn't wait for.
        testScheduler.runCurrent()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.INTERRUPTED)
    }

    @Test fun pendingTurnFromAnEarlierProcessShowsInterrupted() = runTest(main.dispatcher) {
        val chatId = chats.create("How do plants use light?")
        val stale = chats.startTurn(chatId, "How do plants use light?", askedDeeper = false, quote = null)
        chats.interruptPending()
        val vm = vm(chatId = chatId)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.title).isEqualTo("How do plants use light?")
        assertThat(vm.state.value.turns.single().turn.outcome).isEqualTo(TurnOutcome.INTERRUPTED)
        vm.retry(stale)
        testScheduler.advanceUntilIdle()
        val retried = chats.turnsOf(chatId).last()
        assertThat(retried.id).isNotEqualTo(stale)
        assertThat(retried.question).isEqualTo("How do plants use light?")
        assertThat(retried.outcome).isEqualTo(TurnOutcome.ANSWERED)
    }

    @Test fun generatorErrorMidAnswerFailsTheTurn() = runTest(main.dispatcher) {
        small.answer = listOf("Plants use light [1].", " And")
        small.failAt = 1
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.FAILED)
        assertThat(vm.state.value.running).isFalse()
    }

    @Test fun searchFailureShowsRetryNotRepair() = runTest(main.dispatcher) {
        finder.failure = IllegalStateException("disk I/O error")
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.SEARCH_FAILED)
        assertThat(vm.state.value.running).isFalse()
        // Retry asks it again, and the search works this time.
        finder.failure = null
        vm.retry(turn.id)
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnsOf(turn.chatId).last().outcome).isEqualTo(TurnOutcome.ANSWERED)
    }

    @Test fun aModelFailureStaysFailed() = runTest(main.dispatcher) {
        models.smallFails = true
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.FAILED)
    }

    @Test fun failedGuessShowsTheModelNotice() = runTest(main.dispatcher) {
        val vm = vm()
        val id = declined(vm)
        models.smallFails = true
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        val ui = vm.state.value.turns.single()
        assertThat(ui.guessFailed).isTrue()
        assertThat(ui.offersGuess).isTrue()
        assertThat(ui.turn.guess).isEmpty()
        assertThat(ui.turn.outcome).isEqualTo(TurnOutcome.DECLINED)
        // The notice isn't stored: the next try starts clean.
        models.smallFails = false
        vm.bestGuess(id)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().guessFailed).isFalse()
        assertThat(chats.turnList.value.single().guess).isNotEmpty()
    }

    @Test fun stoppedTurnKeepsPipelineMedicalFlag() = runTest(main.dispatcher) {
        // The question itself doesn't look medical; the model's rewrite says it is.
        small.rewrite = "keywords: photosynthesis, light\nmedical: yes"
        finder.gate = CompletableDeferred()
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns.single().stage).isEqualTo(Stage.SEARCHING)
        vm.stop()
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.INTERRUPTED)
        assertThat(turn.medical).isTrue()
    }

    @Test fun failedTurnKeepsPipelineMedicalFlag() = runTest(main.dispatcher) {
        small.rewrite = "keywords: photosynthesis, light\nmedical: yes"
        small.failAt = 0
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.FAILED)
        assertThat(turn.medical).isTrue()
    }

    @Test fun turnsFlowErrorDoesNotCrash() = runTest(main.dispatcher) {
        val chatId = chats.create("How do plants use light?")
        chats.flowFailure = IllegalStateException("disk I/O error")
        val vm = vm(chatId = chatId)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.turns).isEmpty()
        assertThat(vm.state.value.recentChats).isEmpty()
    }

    @Test fun deleteChatErrorDoesNotCrash() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        chats.failure = IllegalStateException("disk I/O error")
        vm.deleteChat()
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
        assertThat(vm.state.value.chatId).isNull()
        assertThat(vm.state.value.turns).isEmpty()
    }

    @Test fun aStorageErrorLeavesTheChatUsable() = runTest(main.dispatcher) {
        chats.failure = IllegalStateException("disk I/O error")
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.running).isFalse()
        assertThat(chats.chats.value).isEmpty()
        chats.failure = null
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.ANSWERED)
    }

    @Test fun aStorageErrorSettlingAFailedTurnIsSurvived() = runTest(main.dispatcher) {
        small.answer = listOf("Plants use light [1].", " And")
        small.gateAt = 1
        small.failAt = 1
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        chats.failure = IllegalStateException("disk I/O error")
        small.gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.running).isFalse()
        assertThat(chats.turnList.value.single().outcome).isEqualTo(TurnOutcome.PENDING)
    }

    @Test fun aStorageErrorOpeningAChatIsSurvived() = runTest(main.dispatcher) {
        val chatId = chats.create("How do plants use light?")
        chats.failure = IllegalStateException("disk I/O error")
        val vm = vm(chatId = chatId)
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.title).isEmpty()
    }

    @Test fun longQuestionIsCut() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("  " + "light ".repeat(300))
        testScheduler.advanceUntilIdle()
        val question = chats.turnList.value.single().question
        assertThat(question.length).isAtMost(1_000)
        assertThat(question).startsWith("light light")
        assertThat(small.prompts.first()).contains("Question: $question")
    }

    @Test fun thinkDeeperUsesShownAndCandidates() = runTest(main.dispatcher) {
        val large = ScriptedGenerator(listOf("Light drives photosynthesis [2][7]."))
        models.large = large
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.canThinkDeeper).isTrue()
        val first = chats.turnList.value.single()
        vm.thinkDeeper(first.id)
        testScheduler.advanceUntilIdle()
        assertThat(finder.calls).isEqualTo(1)
        val deeper = chats.turnList.value.last()
        assertThat(deeper.question).isEqualTo(first.question)
        assertThat(deeper.askedDeeper).isTrue()
        assertThat(deeper.usedLarge).isTrue()
        assertThat(deeper.outcome).isEqualTo(TurnOutcome.ANSWERED)
        assertThat(deeper.shown).containsExactlyElementsIn(first.shown + first.candidates).inOrder()
        assertThat(deeper.shown).hasSize(8)
        assertThat(large.answerPrompts).hasSize(1)
        assertThat(vm.state.value.turns.last().largeFallback).isFalse()
        assertThat(vm.state.value.canThinkDeeper).isFalse()
    }

    @Test fun thinkDeeperFallbackIsFlagged() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        vm.thinkDeeper(chats.turnList.value.single().id)
        testScheduler.advanceUntilIdle()
        val deeper = chats.turnList.value.last()
        assertThat(deeper.askedDeeper).isTrue()
        assertThat(deeper.usedLarge).isFalse()
        assertThat(deeper.outcome).isEqualTo(TurnOutcome.ANSWERED)
        assertThat(vm.state.value.turns.last().largeFallback).isTrue()
        assertThat(vm.state.value.turns.first().largeFallback).isFalse()
    }

    @Test fun openingACitationWhoseSourceIsGoneSaysSo() = runTest(main.dispatcher) {
        val vm = vm()
        val bookPassage = Passage(SourceKind.BOOK, "book:granger:emma", "Emma", location = "ch1.xhtml", text = "Emma Woodhouse")
        assertThat(vm.open(bookPassage)).isEqualTo(CitationTarget.Gone)
        library.packList.value = listOf(pack("pack1", status = PackStatus.MISSING))
        assertThat(vm.open(article(1))).isEqualTo(CitationTarget.Gone)
        bookshelf.bookList.value = listOf(book("book:granger:emma", status = PackStatus.CORRUPT))
        assertThat(vm.open(bookPassage)).isEqualTo(CitationTarget.Gone)
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun aCitationToAVanishedTabSaysTheSourceIsGone() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("pack1"))
        val vanishing = object : ReadingSessions by sessions {
            override suspend fun tab(id: Long) = null
        }
        assertThat(vm(sessions = vanishing).open(article(1))).isEqualTo(CitationTarget.Gone)
    }

    @Test fun aCitationThatCantBeOpenedIsGoneRatherThanACrash() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("pack1"))
        val broken = object : ReadingSessions by sessions {
            override suspend fun openTab(packId: String, path: String, title: String): Long = throw IllegalStateException("disk I/O error")
        }
        val vm = vm(sessions = broken)
        assertThat(vm.open(article(1))).isEqualTo(CitationTarget.Gone)
    }

    @Test fun articleCitationCarriesTheAnchor() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("pack1"))
        val vm = vm()
        val target = vm.open(article(1)) as CitationTarget.Article
        assertThat(target.fragment).isEqualTo("sec1")
        val tab = sessions.tabs.value.single { it.id == target.tabId }
        assertThat(tab.packId to tab.path).isEqualTo("pack1" to "A/Art1")
        val card = vm.open(article(1).copy(kind = SourceKind.CARD, location = "x", anchor = null)) as CitationTarget.Article
        assertThat(sessions.tabs.value.single { it.id == card.tabId }.path).isEmpty()
    }

    @Test fun aCitationOpensByTurnAndNumberWithTheAnswersQuote() = runTest(main.dispatcher) {
        library.packList.value = (1..10).map { pack("pack$it") }
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        val turn = chats.turnList.value.single()
        val first = turn.shown.first()
        val target = vm.open(turn.id, 1) as CitationTarget.Article
        assertThat(target.quote).isEqualTo(first.text)
        assertThat(sessions.tabs.value.single { it.id == target.tabId }.packId).isEqualTo(first.sourceId)
        assertThat(vm.open(turn.id, 99)).isEqualTo(CitationTarget.Gone)
        assertThat(vm.open(-1, 1)).isEqualTo(CitationTarget.Gone)
    }

    @Test fun bookCitationOpensAtThePassage() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:emma", title = "Emma"))
        val vm = vm()
        val text = "Emma Woodhouse, handsome, clever, and rich, with a comfortable home and happy disposition, seemed to unite"
        val passage = Passage(SourceKind.BOOK, "book:granger:emma", "Emma", location = "ch1.xhtml", progression = 0.25, text = text)
        val target = vm.open(passage) as CitationTarget.Book
        assertThat(target.at).isEqualTo(
            BookTarget("ch1.xhtml", 0.25, TextQuote("", text, "")),
        )
        assertThat(sessions.tabs.value.single { it.id == target.tabId }.packId).isEqualTo("book:granger:emma")
        val card = vm.open(passage.copy(kind = SourceKind.CARD)) as CitationTarget.Book
        assertThat(card.at).isNull()
        val selection = vm.open(passage.copy(kind = SourceKind.SELECTION, location = "")) as CitationTarget.Book
        assertThat(selection.at).isNull()
        val placed = vm.open(passage.copy(kind = SourceKind.SELECTION)) as CitationTarget.Book
        assertThat(placed.at?.href).isEqualTo("ch1.xhtml")
    }

    @Test fun mediaAlbumCitationOpensTheAlbum() = runTest(main.dispatcher) {
        val planets = song("mars", "Mars", album = "The Planets", albumArtists = "Gustav Holst")
        mediaLibrary.trackList.value = listOf(planets)
        val target = vm().open(media(1, planets.albumKey))
        assertThat(target).isEqualTo(CitationTarget.Album(planets.albumKey))
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun mediaAudiobookCitationOpensTheBook() = runTest(main.dispatcher) {
        val id = "audiobook:phone:jungle"
        mediaLibrary.bookList.value = listOf(
            Audiobook(id, MediaOrigin.PHONE, "jungle", "The Jungle Book", "Rudyard Kipling", "", "", null, 0L, PackStatus.OK, 0L),
        )
        val target = vm().open(mediaBook(id, "The Jungle Book", "Rudyard Kipling"))
        assertThat(target).isEqualTo(CitationTarget.Audiobook(id))
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun removedMediaCitationIsGone() = runTest(main.dispatcher) {
        val planets = song("mars", "Mars", album = "The Planets", albumArtists = "Gustav Holst", status = PackStatus.MISSING)
        mediaLibrary.trackList.value = listOf(planets)
        val id = "audiobook:phone:jungle"
        mediaLibrary.bookList.value = listOf(
            Audiobook(id, MediaOrigin.PHONE, "jungle", "The Jungle Book", "Rudyard Kipling", "", "", null, 0L, PackStatus.MISSING, 0L),
        )
        val vm = vm()
        assertThat(vm.open(media(1, planets.albumKey))).isEqualTo(CitationTarget.Gone)
        assertThat(vm.open(mediaBook(id))).isEqualTo(CitationTarget.Gone)
        assertThat(vm.open(media(2, "never-there"))).isEqualTo(CitationTarget.Gone)
    }

    @Test fun mediaIsNeverTreatedAsAPack() = runTest(main.dispatcher) {
        // A pack whose id is the media card's sourceId: the citation still isn't opened as an article.
        library.packList.value = listOf(pack("album:key1"))
        val vm = vm()
        assertThat(vm.open(media(1))).isEqualTo(CitationTarget.Gone)
        assertThat(sessions.tabs.value).isEmpty()
    }

    @Test fun mediaSourceCardBylineSaysAlbumOrAudiobook() {
        assertThat(sourceByline(media(1))).isEqualTo("Artist 1 · Album")
        assertThat(sourceByline(mediaBook("audiobook:phone:x", author = "Rudyard Kipling"))).isEqualTo("Rudyard Kipling · Audiobook")
        assertThat(sourceByline(mediaBook("audiobook:phone:x", author = ""))).isEqualTo("Your audiobooks · Audiobook")
        assertThat(sourceByline(article(1))).isEqualTo("Pack 1")
    }

    @Test fun deletingTheChatRemovesIt() = runTest(main.dispatcher) {
        val vm = vm()
        vm.ask("How do plants use light?")
        testScheduler.advanceUntilIdle()
        vm.deleteChat()
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent()
        assertThat(chats.chats.value).isEmpty()
        assertThat(vm.state.value.chatId).isNull()
        assertThat(vm.state.value.turns).isEmpty()
    }

    @Test fun recentChatsAreListed() = runTest(main.dispatcher) {
        repeat(12) { chats.create("Chat $it") }
        val vm = vm()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.recentChats).hasSize(10)
        assertThat(vm.state.value.recentChats.first().title).isEqualTo("Chat 11")
    }

    @Test fun modelsMissingReadinessIsExposed() = runTest(main.dispatcher) {
        installed.value = InstalledModels()
        val vm = vm()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.readiness).isEqualTo(ResearchReadiness.MODELS_MISSING)
        assertThat(vm.state.value.canThinkDeeper).isFalse()
    }
}
