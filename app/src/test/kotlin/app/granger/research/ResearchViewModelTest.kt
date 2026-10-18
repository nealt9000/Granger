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
import app.granger.core.library.PackStatus
import app.granger.core.library.TurnOutcome
import app.granger.core.research.Passage
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
    ): ResearchViewModel {
        return ResearchViewModel(
            chatId, quote, chats, ResearchPipeline(finder, models), manager, sessions, library, bookshelf, backgroundScope,
            savedState,
        )
    }

    private fun TestScope.manager(llms: LlmEngineFactory) = ModelManager(
        DeviceTier.THINK_DEEPER, installed, llms, TextEmbedderFactory { error("no embedder") }, backgroundScope,
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

    @Test fun bookCitationOpensAtThePassage() = runTest(main.dispatcher) {
        bookshelf.bookList.value = listOf(book("book:granger:emma", title = "Emma"))
        val vm = vm()
        val text = "Emma Woodhouse, handsome, clever, and rich, with a comfortable home and happy disposition, seemed to unite"
        val passage = Passage(SourceKind.BOOK, "book:granger:emma", "Emma", location = "ch1.xhtml", progression = 0.25, text = text)
        val target = vm.open(passage) as CitationTarget.Book
        assertThat(target.at).isEqualTo(
            BookTarget("ch1.xhtml", 0.25, TextQuote("", "Emma Woodhouse, handsome, clever, and rich, with a comfortable home and happy", "")),
        )
        assertThat(sessions.tabs.value.single { it.id == target.tabId }.packId).isEqualTo("book:granger:emma")
        val card = vm.open(passage.copy(kind = SourceKind.CARD)) as CitationTarget.Book
        assertThat(card.at).isNull()
        val selection = vm.open(passage.copy(kind = SourceKind.SELECTION, location = "")) as CitationTarget.Book
        assertThat(selection.at).isNull()
        val placed = vm.open(passage.copy(kind = SourceKind.SELECTION)) as CitationTarget.Book
        assertThat(placed.at?.href).isEqualTo("ch1.xhtml")
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
