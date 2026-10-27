package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResearchPipelineTest {
    private val water = passage("W", "Water boils at 100 degrees at sea level.", "Water")
    private val kettle = passage("K", "A kettle brings water to the boil quickly.", "Kettles")
    private val moon = passage("M", "The moon orbits the earth every month.", "Moon")

    private fun passage(source: String, text: String, title: String, n: Int = 0) =
        Passage(SourceKind.BOOK, source, title, location = "$source-$n", text = text)

    private val quote = Passage(SourceKind.SELECTION, "Q", "Quoted Book", text = "It was the best of times.")

    /** Answers rewrite prompts with [rewrite] and answer prompts with [answer]; records every prompt. */
    private class FakeGenerator(
        val rewrite: (String) -> Flow<String> = { flowOf("keywords: water, boils\n", "medical: no") },
        val answer: (String) -> Flow<String> = { flowOf("Water boils at 100 °C [1].") },
    ) : TextGenerator {
        val prompts = mutableListOf<String>()
        val maxTokens = mutableListOf<Int>()
        override fun generate(prompt: String, maxTokens: Int): Flow<String> {
            prompts += prompt
            this.maxTokens += maxTokens
            return if (prompt.startsWith("Turn the question")) rewrite(prompt) else answer(prompt)
        }
        val answerPrompts get() = prompts.filterNot { it.startsWith("Turn the question") }
    }

    private class FakeModels(
        val small: TextGenerator?,
        val large: TextGenerator? = null,
        val embedder: PassageEmbedder? = null,
    ) : ResearchModels {
        val requested = mutableListOf<Boolean>()
        override suspend fun generator(deeper: Boolean): TextGenerator {
            requested += deeper
            val g = if (deeper) large else small
            return g ?: throw ModelUnavailableException("no model")
        }
        override suspend fun embedder(): PassageEmbedder? = embedder
    }

    private class FakeFinder(val results: List<Passage>) : PassageFinder {
        val calls = mutableListOf<List<String>>()
        override suspend fun find(keywords: List<String>): List<Passage> {
            calls += keywords
            return results
        }
    }

    /** Anything about water points one way, everything else the other. */
    private val embedder = PassageEmbedder { texts, _ ->
        texts.map { if (it.contains("water", ignoreCase = true)) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
    }

    private fun outcome(events: List<ResearchEvent>): Outcome =
        (events.last() as ResearchEvent.Done).outcome

    @Test
    fun plainRewriteAsksNoModelForAFirstQuestionButDoesForAFollowUp() = runTest {
        val gen = FakeGenerator()
        val finder = FakeFinder(listOf(water))
        val pipeline = ResearchPipeline(finder, FakeModels(gen, embedder = embedder), rewritePlain = true)
        val out = outcome(pipeline.run(ResearchRequest("At what temperature does water boil?")).toList()) as Outcome.Answered
        assertThat(out.keywords).containsExactly("temperature", "water", "boil").inOrder()
        assertThat(gen.prompts.filter { it.startsWith("Turn the question") }).isEmpty()
        pipeline.run(ResearchRequest("And on a mountain?", priorQuestion = "At what temperature does water boil?")).toList()
        assertThat(gen.prompts.filter { it.startsWith("Turn the question") }).hasSize(1)
    }

    @Test
    fun answersWithValidCitations() = runTest {
        val gen = FakeGenerator()
        val finder = FakeFinder(listOf(water))
        val events = ResearchPipeline(finder, FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.cited).containsExactly(1)
        assertThat(out.text).isEqualTo("Water boils at 100 °C [1].")
        assertThat(out.passages).containsExactly(water)
        assertThat(out.usedLarge).isFalse()
        assertThat(out.keywords).containsExactly("water", "boils").inOrder()
        assertThat(finder.calls).containsExactly(listOf("water", "boils"))
        assertThat(gen.maxTokens).containsExactly(32, 512).inOrder()
        assertThat(events.filterIsInstance<ResearchEvent.Partial>().last().text).isEqualTo("Water boils at 100 °C [1].")
    }

    @Test
    fun noPassagesDeclinesWithoutCallingTheModel() = runTest {
        val gen = FakeGenerator()
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(outcome(events)).isInstanceOf(Outcome.Declined::class.java)
        assertThat(gen.prompts).hasSize(1)
        assertThat(events.filterIsInstance<ResearchEvent.Progress>().map { it.stage }).doesNotContain(Stage.WRITING)
    }

    @Test
    fun belowThresholdDeclines() = runTest {
        val gen = FakeGenerator()
        val events = ResearchPipeline(FakeFinder(listOf(moon)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(outcome(events)).isInstanceOf(Outcome.Declined::class.java)
        assertThat(gen.answerPrompts).isEmpty()
    }

    @Test
    fun invalidCitationsGiveUnverifiedWithPassages() = runTest {
        val gen = FakeGenerator(answer = { flowOf("Water boils [7].") })
        val events = ResearchPipeline(FakeFinder(listOf(water, kettle)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        val out = outcome(events) as Outcome.Unverified
        assertThat(out.text).isEqualTo("Water boils [7].")
        assertThat(out.passages).containsExactly(water, kettle)
        assertThat(out.candidates).isEmpty()
    }

    @Test
    fun stoppedReplyWithoutCitationsFallsBackToPassages() = runTest {
        val gen = FakeGenerator(answer = { flowOf("Water ", "boils ", "at ", "one ", "hundred.") })
        var partials = 0
        val seen = mutableListOf<ResearchEvent>()
        ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?"), stop = { partials >= 3 })
            .collect {
                if (it is ResearchEvent.Partial) partials++
                seen += it
            }
        val out = outcome(seen) as Outcome.Unverified
        assertThat(out.text).isEqualTo("Water boils at ")
        assertThat(out.passages).containsExactly(water)
        assertThat(seen.filterIsInstance<ResearchEvent.Partial>()).hasSize(3)
    }

    @Test
    fun stoppedReplyWithCitationsIsAnswered() = runTest {
        val gen = FakeGenerator(answer = { flowOf("Water boils [1]", " at sea level", " and more.") })
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?"), stop = { true }).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.text).isEqualTo("Water boils [1]")
    }

    @Test
    fun collectorCancellationPropagates() = runTest {
        val gen = FakeGenerator(answer = { flowOf("Water ", "boils [1].") })
        val first = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?"))
            .first { it is ResearchEvent.Partial }
        assertThat((first as ResearchEvent.Partial).text).isEqualTo("Water ")
    }

    @Test
    fun modelDeclineTokenIsDeclined() = runTest {
        val gen = FakeGenerator(answer = { flowOf(AnswerPrompt.DECLINE_TOKEN) })
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(outcome(events)).isInstanceOf(Outcome.Declined::class.java)
    }

    @Test
    fun rewriteTimeoutFallsBackToKeywords() = runTest {
        val gen = FakeGenerator(rewrite = { flow { awaitCancellation() } })
        val finder = FakeFinder(listOf(water))
        val question = "At what temperature does water boil?"
        val events = ResearchPipeline(finder, FakeModels(gen, embedder = embedder)).run(ResearchRequest(question)).toList()
        assertThat(finder.calls).containsExactly(Keywords.extract(question))
        assertThat(outcome(events).keywords).isEqualTo(Keywords.extract(question))
        assertThat(outcome(events)).isInstanceOf(Outcome.Answered::class.java)
    }

    @Test
    fun finderExceptionFailsWithSearchFailed() = runTest {
        val finder = PassageFinder { throw IllegalStateException("disk I/O error") }
        val events = ResearchPipeline(finder, FakeModels(FakeGenerator())).run(ResearchRequest("At what temperature does water boil?")).toList()
        val out = outcome(events) as Outcome.Failed
        assertThat(out.reason).isEqualTo(FailReason.SEARCH_FAILED)
        assertThat(out.cause).hasMessageThat().isEqualTo("disk I/O error")
        assertThat(out.keywords).containsExactly("water", "boils").inOrder()
    }

    @Test
    fun rewriteTimeoutStartsAfterTheModelIsFree() = runTest {
        // Another chat holds the model for 6 s (longer than the 5 s timeout) before this reply may begin.
        val busy = object : TextGenerator {
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = generate(prompt, maxTokens) {}
            override fun generate(prompt: String, maxTokens: Int, onReady: () -> Unit): Flow<String> = flow {
                delay(6_000)
                onReady()
                emit(if (prompt.startsWith("Turn the question")) "keywords: water, boils\nmedical: no" else "Water boils at 100 °C [1].")
            }
        }
        val finder = FakeFinder(listOf(water))
        val events = ResearchPipeline(finder, FakeModels(busy)).run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(finder.calls).containsExactly(listOf("water", "boils"))
        assertThat(outcome(events).keywords).containsExactly("water", "boils").inOrder()
    }

    @Test
    fun rewriteStillTimesOutOnceTheModelIsFree() = runTest {
        val slow = object : TextGenerator {
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = generate(prompt, maxTokens) {}
            override fun generate(prompt: String, maxTokens: Int, onReady: () -> Unit): Flow<String> = flow {
                onReady()
                if (prompt.startsWith("Turn the question")) awaitCancellation()
                emit("Water boils at 100 °C [1].")
            }
        }
        val finder = FakeFinder(listOf(water))
        val question = "At what temperature does water boil?"
        ResearchPipeline(finder, FakeModels(slow)).run(ResearchRequest(question)).toList()
        assertThat(finder.calls).containsExactly(Keywords.extract(question))
    }

    @Test
    fun unparseableRewriteFallsBackToKeywordsAndMedicalLexicon() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("I can't help with that.") })
        val finder = FakeFinder(emptyList())
        val question = "What dose of insulin is safe?"
        val events = ResearchPipeline(finder, FakeModels(gen)).run(ResearchRequest(question)).toList()
        assertThat(finder.calls).containsExactly(Keywords.extract(question))
        assertThat(outcome(events).medical).isTrue()
    }

    @Test
    fun rewriteCutOffBeforeItsMedicalLineFallsBackToTheLexicon() = runTest {
        // A long keyword line uses up the 32 tokens before "medical:" is written.
        val gen = FakeGenerator(rewrite = { flowOf("keywords: burn treatment, first aid for burns, burn care, treating") })
        val finder = FakeFinder(emptyList())
        val events = ResearchPipeline(finder, FakeModels(gen)).run(ResearchRequest("How do I treat a burn?")).toList()
        assertThat(finder.calls.single()).contains("burn treatment")
        assertThat(outcome(events).medical).isTrue()
    }

    @Test
    fun medicalFromRewrite() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: water, boils\nmedical: yes") })
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(outcome(events).medical).isTrue()
        assertThat(gen.answerPrompts.single()).contains("This is a medical question.")
    }

    @Test
    fun medicalIsFlaggedBeforeTheReplyStreams() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: water, boils\nmedical: yes") })
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        val flagged = events.indexOf(ResearchEvent.Medical)
        assertThat(flagged).isAtLeast(0)
        assertThat(flagged).isLessThan(events.indexOfFirst { it is ResearchEvent.Partial })
    }

    @Test
    fun deeperFlagsMedicalFromTheHint() = runTest {
        val eight = (1..8).map { passage("D$it", "Deep passage $it.", "Deep $it") }
        val large = FakeGenerator(answer = { flowOf("Deep answer [8].") })
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(FakeGenerator(), large = large))
            .run(ResearchRequest("Why?", deeperFrom = eight, medicalHint = true)).toList()
        assertThat(events.indexOf(ResearchEvent.Medical)).isLessThan(events.indexOfFirst { it is ResearchEvent.Partial })
        assertThat(events).contains(ResearchEvent.Medical)
    }

    @Test
    fun notMedicalIsNotFlagged() = runTest {
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(events).doesNotContain(ResearchEvent.Medical)
    }

    @Test
    fun rewriteKeywordsAreNormalized() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: Darwin’s finches, Darwin's, , Galapagos\nmedical: no") })
        val finder = FakeFinder(emptyList())
        ResearchPipeline(finder, FakeModels(gen)).run(ResearchRequest("What are Darwin's finches?")).toList()
        assertThat(finder.calls).containsExactly(listOf("darwin finches", "darwin", "galapagos"))
    }

    @Test
    fun lexicalRankingWhenNoEmbedder() = runTest {
        val gen = FakeGenerator()
        val events = ResearchPipeline(FakeFinder(listOf(moon, water)), FakeModels(gen, embedder = null))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.passages).containsExactly(water)
    }

    @Test
    fun quoteIsPassageOneEvenWithNoOtherHits() = runTest {
        val gen = FakeGenerator()
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("What does this mean?", quote = quote)).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.passages).containsExactly(quote)
        val prompt = gen.answerPrompts.single()
        assertThat(prompt).contains("[1] Quoted Book")
        assertThat(prompt).contains("It was the best of times.")
        assertThat(prompt).contains("The user selected passage [1] and is asking about it.")
    }

    @Test
    fun quoteLeadsRankedPassagesAndCandidatesHoldTheRest() = runTest {
        val many = (1..10).map { passage("S$it", "Water boils, entry $it.", "Water $it") }
        val gen = FakeGenerator()
        val events = ResearchPipeline(FakeFinder(many), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?", quote = quote)).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.passages).hasSize(4)
        assertThat(out.passages.first()).isEqualTo(quote)
        assertThat(out.passages.size + out.candidates.size).isEqualTo(8)
        assertThat(out.candidates).doesNotContain(quote)
        assertThat(out.candidates.intersect(out.passages.toSet())).isEmpty()
        assertThat(gen.answerPrompts.single()).doesNotContain("[5]")
    }

    @Test
    fun nonMediaQuestionPoolMatchesThePoolWithoutMedia() = runTest {
        fun book(n: Int) = passage("S$n", "Water boils, entry $n.", "Water $n")
        fun media(n: Int) = Passage(SourceKind.MEDIA, "album:a$n", "Water Music $n", packTitle = "Your music", text = "Album: Water boils $n")
        val books = (1..10).map { book(it) }
        val withMedia = listOf(media(1), books[0], media(2), books[1], media(3)) + books.drop(2)
        val withoutMedia = listOf(media(1)) + books
        suspend fun pool(found: List<Passage>): List<Passage> {
            val events = ResearchPipeline(FakeFinder(found), FakeModels(FakeGenerator(), embedder = null))
                .run(ResearchRequest("At what temperature does water boil?")).toList()
            val out = outcome(events) as Outcome.Answered
            return out.passages + out.candidates
        }
        val capped = pool(withMedia)
        assertThat(capped).isEqualTo(pool(withoutMedia))
        assertThat(capped).hasSize(8)
        assertThat(capped.count { it.kind == SourceKind.MEDIA }).isEqualTo(1)
    }

    @Test
    fun mediaQuestionKeepsTwoMediaPassages() = runTest {
        fun book(n: Int) = passage("S$n", "Water boils, entry $n.", "Water $n")
        fun media(n: Int) = Passage(SourceKind.MEDIA, "album:a$n", "Water Music $n", packTitle = "Your music", text = "Album: Water boils $n")
        val found = listOf(media(1), media(2), media(3)) + (1..10).map { book(it) }
        val events = ResearchPipeline(FakeFinder(found), FakeModels(FakeGenerator(), embedder = null))
            .run(ResearchRequest("Which album has water boils on it?")).toList()
        val out = outcome(events) as Outcome.Answered
        assertThat((out.passages + out.candidates).filter { it.kind == SourceKind.MEDIA }).containsExactly(media(1), media(2)).inOrder()
    }

    @Test
    fun mediaQuestionPutsTheMediaCardFirstEvenWhenRankingDropsIt() = runTest {
        fun book(n: Int) = passage("S$n", "Water boils, entry $n.", "Water $n")
        // No keyword word in the card: lexical ranking drops it.
        val card = Passage(SourceKind.MEDIA, "album:a", "Handel", packTitle = "Your music", text = "Album: Handel")
        val found = (1..10).map { book(it) } + card
        suspend fun passages(question: String): List<Passage> {
            val events = ResearchPipeline(FakeFinder(found), FakeModels(FakeGenerator(), embedder = null))
                .run(ResearchRequest(question)).toList()
            return (outcome(events) as Outcome.Answered).passages
        }
        assertThat(passages("Which album has water boils on it?").first()).isEqualTo(card)
        assertThat(passages("At what temperature does water boil?")).doesNotContain(card)
    }

    @Test
    fun followUpRewriteSeesPreviousQuestion() = runTest {
        val gen = FakeGenerator()
        ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
            .run(ResearchRequest("And at altitude?", priorQuestion = "When does water boil?")).toList()
        assertThat(gen.prompts.first()).contains("Previous question: When does water boil?")
    }

    @Test
    fun followUpFallbackKeywordsIncludePreviousQuestion() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("nonsense") })
        val finder = FakeFinder(emptyList())
        ResearchPipeline(finder, FakeModels(gen))
            .run(ResearchRequest("And at altitude?", priorQuestion = "When does water boil?")).toList()
        assertThat(finder.calls).containsExactly(Keywords.extract("And at altitude? When does water boil?"))
    }

    @Test
    fun deeperSkipsRetrievalAndUsesEight() = runTest {
        val eight = (1..8).map { passage("D$it", "Deep passage $it.", "Deep $it") }
        val small = FakeGenerator()
        val large = FakeGenerator(answer = { flowOf("Deep answer [8].") })
        val finder = FakeFinder(listOf(water))
        val models = FakeModels(small, large = large)
        val events = ResearchPipeline(finder, models)
            .run(ResearchRequest("At what temperature does water boil?", deeperFrom = eight, medicalHint = true)).toList()
        assertThat(finder.calls).isEmpty()
        assertThat(models.requested).containsExactly(true)
        assertThat(small.prompts).isEmpty()
        assertThat(large.prompts).hasSize(1)
        assertThat(large.prompts.single()).contains("[8] Deep 8")
        val out = outcome(events) as Outcome.Answered
        assertThat(out.usedLarge).isTrue()
        assertThat(out.cited).containsExactly(8)
        assertThat(out.passages).isEqualTo(eight)
        assertThat(out.candidates).isEmpty()
        assertThat(out.medical).isTrue()
        assertThat(out.keywords).isEmpty()
    }

    @Test
    fun largeLoadFailureFallsBackToSmall() = runTest {
        val eight = (1..8).map { passage("D$it", "Deep passage $it.", "Deep $it") }
        val small = FakeGenerator()
        val models = FakeModels(small, large = null)
        val events = ResearchPipeline(FakeFinder(emptyList()), models)
            .run(ResearchRequest("Why?", deeperFrom = eight)).toList()
        assertThat(events).contains(ResearchEvent.LargeFallback)
        assertThat(models.requested).containsExactly(true, false).inOrder()
        val out = outcome(events) as Outcome.Answered
        assertThat(out.usedLarge).isFalse()
        assertThat(out.passages).isEqualTo(eight.take(4))
        assertThat(out.candidates).isEqualTo(eight.drop(4))
    }

    @Test
    fun smallLoadFailureFails() = runTest {
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(small = null))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        val out = outcome(events) as Outcome.Failed
        assertThat(out.reason).isEqualTo(FailReason.MODEL_UNAVAILABLE)
    }

    @Test
    fun stagesInOrder() = runTest {
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = embedder))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat(events.filterIsInstance<ResearchEvent.Progress>().map { it.stage })
            .containsExactly(Stage.LOADING_MODEL, Stage.SEARCHING, Stage.RANKING, Stage.WRITING).inOrder()
        assertThat(events.last()).isInstanceOf(ResearchEvent.Done::class.java)
    }

    @Test
    fun guessStreamsPartialsThenGuessed() = runTest {
        val gen = FakeGenerator(answer = { flowOf("Probably ", "Paris.") })
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(gen))
            .guess("Capital of France?", priorQuestion = null, large = false).toList()
        assertThat(events.take(2)).containsExactly(
            ResearchEvent.Progress(Stage.LOADING_MODEL),
            ResearchEvent.Progress(Stage.WRITING),
        ).inOrder()
        assertThat(events.filterIsInstance<ResearchEvent.Partial>().map { it.text })
            .containsExactly("Probably ", "Probably Paris.").inOrder()
        val out = outcome(events) as Outcome.Guessed
        assertThat(out.text).isEqualTo("Probably Paris.")
        assertThat(out.usedLarge).isFalse()
        assertThat(out.fellBack).isFalse()
        assertThat(out.medical).isFalse()
        assertThat(out.keywords).isEmpty()
        assertThat(gen.maxTokens).containsExactly(512)
    }

    @Test
    fun guessNeverCallsTheFinder() = runTest {
        val finder = FakeFinder(listOf(water))
        ResearchPipeline(finder, FakeModels(FakeGenerator()))
            .guess("Anything?", priorQuestion = "Before?", large = false).toList()
        assertThat(finder.calls).isEmpty()
    }

    @Test
    fun guessFallsBackToSmallWhenLargeWontLoad() = runTest {
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(small = FakeGenerator(), large = null))
            .guess("Anything?", priorQuestion = null, large = true).toList()
        assertThat(events).contains(ResearchEvent.GuessFallback)
        val out = outcome(events) as Outcome.Guessed
        assertThat(out.fellBack).isTrue()
        assertThat(out.usedLarge).isFalse()
    }

    @Test
    fun guessStopKeepsTheTextSoFar() = runTest {
        val gen = FakeGenerator(answer = { flowOf("One ", "two ", "three") })
        var chunks = 0
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(gen))
            .guess("Count?", priorQuestion = null, large = false, stop = { ++chunks >= 2 }).toList()
        assertThat((outcome(events) as Outcome.Guessed).text).isEqualTo("One two ")
    }

    @Test
    fun guessWithNoModelFails() = runTest {
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(small = null))
            .guess("Anything?", priorQuestion = null, large = false).toList()
        val failed = outcome(events) as Outcome.Failed
        assertThat(failed.reason).isEqualTo(FailReason.MODEL_UNAVAILABLE)
        assertThat(failed.keywords).isEmpty()
        assertThat(failed.cause).isInstanceOf(ModelUnavailableException::class.java)
    }

    @Test
    fun guessUsesTheLargeModelWhenItLoads() = runTest {
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(small = FakeGenerator(), large = FakeGenerator()))
            .guess("Anything?", priorQuestion = null, large = true).toList()
        assertThat(events).doesNotContain(ResearchEvent.GuessFallback)
        val out = outcome(events) as Outcome.Guessed
        assertThat(out.usedLarge).isTrue()
        assertThat(out.fellBack).isFalse()
    }

    @Test
    fun largeReloadFailureMidAnswerFallsBackToSmall() = runTest {
        val eight = (1..8).map { passage("D$it", "Deep passage $it.", "Deep $it") }
        val small = FakeGenerator(answer = { flowOf("Small answer [1].") })
        // The large model loaded for the request but won't reload when the reply starts.
        val large = FakeGenerator(answer = { flow { throw ModelUnavailableException("won't reload") } })
        val models = FakeModels(small, large = large)
        val events = ResearchPipeline(FakeFinder(emptyList()), models)
            .run(ResearchRequest("Why?", deeperFrom = eight)).toList()
        assertThat(events).contains(ResearchEvent.LargeFallback)
        val out = outcome(events) as Outcome.Answered
        assertThat(out.text).isEqualTo("Small answer [1].")
        assertThat(out.usedLarge).isFalse()
        assertThat(out.passages).isEqualTo(eight.take(4))
        assertThat(out.candidates).isEqualTo(eight.drop(4))
    }

    @Test
    fun aModelDeclineCarriesItsPassagesAndReply() = runTest {
        val found = listOf(passage("water", "Water boils at 100 degrees.", "Water"))
        val gen = FakeGenerator(answer = { flowOf("NOT IN LIBRARY") })
        val events = ResearchPipeline(FakeFinder(found), FakeModels(gen)).run(ResearchRequest("Water boils?")).toList()
        val out = outcome(events) as Outcome.Declined
        assertThat(out.reply).isEqualTo("NOT IN LIBRARY")
        assertThat(out.passages).isNotEmpty()
    }

    @Test
    fun proseDeclineEndsAsDeclinedWithItsPassages() = runTest {
        val found = listOf(passage("water", "Water boils at 100 degrees.", "Water"))
        val reply = "The provided passages do not contain information about that [1]."
        val gen = FakeGenerator(answer = { flowOf(reply) })
        val events = ResearchPipeline(FakeFinder(found), FakeModels(gen)).run(ResearchRequest("Water boils?")).toList()
        val out = outcome(events) as Outcome.Declined
        assertThat(out.reply).isEqualTo(reply)
        assertThat(out.passages).isNotEmpty()
    }

    @Test
    fun aCodeDeclineCarriesNoPassagesOrReply() = runTest {
        val events = ResearchPipeline(FakeFinder(emptyList()), FakeModels(FakeGenerator())).run(ResearchRequest("Water boils?")).toList()
        val out = outcome(events) as Outcome.Declined
        assertThat(out.passages).isEmpty()
        assertThat(out.reply).isEmpty()
    }

    @Test
    fun personalMedicalQuestionDeclinesWithoutSearching() = runTest {
        val finder = FakeFinder(listOf(water))
        val events = ResearchPipeline(finder, FakeModels(FakeGenerator(), embedder = embedder))
            .run(ResearchRequest("My two year old has a fever of 101. Do I need to take her to the ER?")).toList()
        assertThat(finder.calls).isEmpty()
        assertThat(events).contains(ResearchEvent.Medical)
        val out = outcome(events) as Outcome.Declined
        assertThat(out.medical).isTrue()
    }

    @Test
    fun generalMedicalQuestionStillAnswers() = runTest {
        val pulse = passage("H", "A normal resting heart rate for adults is 60 to 100 beats per minute.", "Heart")
        val gen = FakeGenerator(
            rewrite = { flowOf("keywords: heart rate, resting\nmedical: yes") },
            answer = { flowOf("A normal resting heart rate is 60 to 100 beats per minute [1].") },
        )
        val out = outcome(
            ResearchPipeline(FakeFinder(listOf(pulse)), FakeModels(gen))
                .run(ResearchRequest("what's a normal resting heart rate")).toList(),
        )
        assertThat(out).isInstanceOf(Outcome.Answered::class.java)
        assertThat(out.medical).isTrue()
    }

    @Test
    fun lexiconMarksMedicalWhenRewriteSaysNo() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: stroke, warning signs\nmedical: no") })
        val out = outcome(
            ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder))
                .run(ResearchRequest("What are the warning signs someone is having a stroke?")).toList(),
        )
        assertThat(out.medical).isTrue()
    }

    @Test
    fun quoteQuestionIsNotDeclinedAsPersonal() = runTest {
        val finder = FakeFinder(listOf(water))
        val gen = FakeGenerator(answer = { flowOf("It was the best of times [1].") })
        val out = outcome(
            ResearchPipeline(finder, FakeModels(gen, embedder = embedder))
                .run(ResearchRequest("Do I have to read this closely? My dad says it is a fever dream.", quote = quote)).toList(),
        )
        assertThat(out).isNotInstanceOf(Outcome.Declined::class.java)
    }

    @Test
    fun alternatesVariantSearchesEveryAlternateAndRanksByGroup() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: hot water (steam), boils\n", "medical: no") })
        val finder = FakeFinder(listOf(water))
        val out = outcome(
            ResearchPipeline(finder, FakeModels(gen), rewriteAlternates = true)
                .run(ResearchRequest("At what temperature does water boil?")).toList(),
        ) as Outcome.Answered
        assertThat(out.keywords).containsExactly("hot water|steam", "boils").inOrder()
        assertThat(finder.calls).containsExactly(listOf("hot water", "steam", "boils"))
        assertThat(gen.prompts.first()).contains("in brackets")
        assertThat(gen.maxTokens.first()).isEqualTo(64)
    }

    /** Returns [hits] for any query, recording each one; [fail] throws instead. */
    private class FakeDense(val hits: List<Passage>, val fail: Boolean = false) : DenseFinder {
        val queries = mutableListOf<FloatArray>()
        override suspend fun find(query: FloatArray): List<Passage> {
            queries += query
            if (fail) error("vector file damaged")
            return hits
        }
    }

    @Test
    fun denseHitsReachThePromptAlongsideKeywordPassages() = runTest {
        val boiling = passage("B", "Boiling is a liquid turning to vapour.", "Boiling").copy(cosine = 0.85)
        val embedded = mutableListOf<String>()
        val queryEmbedder = PassageEmbedder { texts, isQuery -> embedded += texts; assertThat(isQuery).isTrue(); texts.map { floatArrayOf(1f, 0f) } }
        val dense = FakeDense(listOf(boiling))
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = queryEmbedder), dense = dense)
            .run(ResearchRequest("At what temperature does water boil?", priorQuestion = "Tell me about water")).toList()
        val out = outcome(events) as Outcome.Answered
        // Water matches every keyword (0.1 × 1.1 + 0.9 × the 0.74 floor); boiling is by meaning alone (0.9 × 0.85).
        assertThat(out.passages).containsExactly(water, boiling).inOrder()
        // A question that stands alone is embedded alone (Plan R5 Task 7); only the query is embedded.
        assertThat(embedded).containsExactly("At what temperature does water boil?")
        assertThat(dense.queries).hasSize(1)
    }

    @Test
    fun aFollowUpIsEmbeddedAfterItsPriorQuestion() = runTest {
        val embedded = mutableListOf<String>()
        val queryEmbedder = PassageEmbedder { texts, _ -> embedded += texts; texts.map { floatArrayOf(1f, 0f) } }
        ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = queryEmbedder), dense = FakeDense(emptyList()))
            .run(ResearchRequest("At what temperature does it boil?", priorQuestion = "Tell me about water")).toList()
        // The prior question comes first in the query, as Plan R1 Task 3 chose.
        assertThat(embedded).containsExactly("Tell me about water At what temperature does it boil?")
    }

    @Test
    fun weakDenseHitsAndPartialKeywordMatchesDecline() = runTest {
        val gen = FakeGenerator(rewrite = { flowOf("keywords: water, boils, kelvin\n", "medical: no") })
        val vague = passage("V", "Liquids can change state.", "States").copy(cosine = 0.75)
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(gen, embedder = embedder), dense = FakeDense(listOf(vague)))
            .run(ResearchRequest("At what temperature in kelvin does water boil?")).toList()
        assertThat(outcome(events)).isInstanceOf(Outcome.Declined::class.java)
        assertThat(gen.answerPrompts).isEmpty()
    }

    @Test
    fun denseFailureFallsBackToKeywordRanking() = runTest {
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = embedder), dense = FakeDense(emptyList(), fail = true))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat((outcome(events) as Outcome.Answered).passages).containsExactly(water)
    }

    @Test
    fun noVectorsMeansKeywordRankingWithoutTheEmbedderRerank() = runTest {
        val reranks = mutableListOf<Int>()
        val counting = PassageEmbedder { texts, isQuery -> if (!isQuery) reranks += texts.size; texts.map { floatArrayOf(1f, 0f) } }
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = counting), dense = FakeDense(emptyList()))
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat((outcome(events) as Outcome.Answered).passages).containsExactly(water)
        assertThat(reranks).isEmpty()
    }

    @Test
    fun noEmbedderMeansKeywordRanking() = runTest {
        val dense = FakeDense(listOf(passage("B", "Boiling is a liquid turning to vapour.", "Boiling").copy(cosine = 0.9)))
        val events = ResearchPipeline(FakeFinder(listOf(water)), FakeModels(FakeGenerator(), embedder = null), dense = dense)
            .run(ResearchRequest("At what temperature does water boil?")).toList()
        assertThat((outcome(events) as Outcome.Answered).passages).containsExactly(water)
        assertThat(dense.queries).isEmpty()
    }
}
