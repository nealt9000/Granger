package app.granger.eval

import app.granger.core.research.ModelUnavailableException
import app.granger.core.research.Passage
import app.granger.core.research.PassageEmbedder
import app.granger.core.research.PassageFinder
import app.granger.core.research.ResearchModels
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.SourceKind
import app.granger.core.research.TextGenerator
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvalRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val water = Passage(SourceKind.BOOK, "book:granger:w", "Water", location = "w1", heading = "Boiling", text = "Water boils at 100 degrees at sea level.")

    private class Gen(val name: String, val answer: (String) -> String = { "Water boils at 100 °C [1]." }) : TextGenerator {
        val prompts = mutableListOf<String>()
        override fun generate(prompt: String, maxTokens: Int): Flow<String> {
            prompts += prompt
            return if (prompt.startsWith("Turn the question")) flowOf("keywords: water, boils\n", "medical: no") else flowOf(answer(prompt))
        }
    }

    private class Models(val small: Gen, val large: Gen) : ResearchModels {
        override suspend fun generator(deeper: Boolean): TextGenerator = if (deeper) large else small
        override suspend fun embedder(): PassageEmbedder? = null
    }

    private class Finder(var results: List<Passage>) : PassageFinder {
        override suspend fun find(keywords: List<String>) = results
    }

    private var ticks = 0L
    private val small = Gen("small")
    private val large = Gen("large")

    private fun runner(results: List<Passage> = listOf(water), source: (Passage) -> String = { it.sourceId }, small: Gen = this.small): EvalRunner {
        val finder = RecordingFinder(Finder(results))
        val prompts = PromptRecorder(Models(small, large))
        return EvalRunner(ResearchPipeline(finder, prompts), finder, source, prompts) { ticks += 10; ticks }
    }

    private fun item(id: String, question: String = "At what temperature does water boil?", prior: String? = null, multiStep: Boolean = false) =
        EvalItem(id, "g", true, false, multiStep, question, prior)

    private fun records(file: File) = file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test
    fun writesOneRecordPerItemWithOutcomeAndTimings() = runTest {
        val out = File(tmp.root, "run1/results.jsonl")
        runner().run(listOf(item("a"), item("b")), out, GuessModels.NONE)
        val recs = records(out)
        assertThat(recs.map { it.getString("id") }).containsExactly("a", "b").inOrder()
        val r = recs[0]
        assertThat(r.getString("runId")).isEqualTo("run1")
        assertThat(r.getString("outcome")).isEqualTo("answered")
        assertThat(r.getJSONArray("cited").getInt(0)).isEqualTo(1)
        assertThat(r.getJSONArray("keywords").getString(0)).isEqualTo("water")
        assertThat(r.getBoolean("rewriteFallback")).isFalse()
        assertThat(r.getJSONArray("chosen").getJSONObject(0).getString("text")).contains("100 degrees")
        val found = r.getJSONArray("found").getJSONObject(0)
        assertThat(found.getString("kind")).isEqualTo("BOOK")
        assertThat(found.has("lexical")).isTrue()
        val ms = r.getJSONObject("ms")
        for (k in listOf("rewrite", "search", "rank", "firstWords", "total")) assertThat(ms.has(k)).isTrue()
        assertThat(ms.getLong("total")).isAtLeast(ms.getLong("firstWords"))
        assertThat(r.has("deeper")).isFalse()
        assertThat(r.has("guesses")).isFalse()
    }

    @Test
    fun recordsPromptsInCallOrderWithStagesAndConcatenatedOutput() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("a")), out, GuessModels.NONE)
        val p = records(out).single().getJSONArray("prompts")
        assertThat((0 until p.length()).map { p.getJSONObject(it).getString("stage") }).containsExactly("rewrite", "answer").inOrder()
        assertThat(p.getJSONObject(0).getString("prompt")).startsWith("Turn the question")
        assertThat(p.getJSONObject(0).getString("output")).isEqualTo("keywords: water, boils\nmedical: no")
        assertThat(p.getJSONObject(1).getString("prompt")).contains("Passages:")
        assertThat(p.getJSONObject(1).getString("output")).isEqualTo("Water boils at 100 °C [1].")
    }

    @Test
    fun promptStagesCoverPriorDeeperAndGuesses() = runTest {
        val declining = Gen("small") { if (it.contains("Passages:")) "NOT IN LIBRARY" else "Maybe." }
        val out = File(tmp.root, "r/results.jsonl")
        runner(small = declining).run(listOf(item("d", prior = "Before?")), out, GuessModels.BOTH)
        val p = records(out).single().getJSONArray("prompts")
        assertThat((0 until p.length()).map { p.getJSONObject(it).getString("stage") })
            .containsExactly("prior-rewrite", "prior-answer", "rewrite", "answer", "guess-small", "guess-large").inOrder()
        val deeperOut = File(tmp.root, "r2/results.jsonl")
        runner().run(listOf(item("m", multiStep = true)), deeperOut, GuessModels.NONE)
        val q = records(deeperOut).single().getJSONArray("prompts")
        assertThat((0 until q.length()).map { q.getJSONObject(it).getString("stage") }).containsExactly("rewrite", "answer", "deeper").inOrder()
    }

    @Test
    fun promptRecordingResetsPerItem() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("a"), item("b")), out, GuessModels.NONE)
        for (r in records(out)) assertThat(r.getJSONArray("prompts").length()).isEqualTo(2)
    }

    @Test
    fun recorderForwardsOnReadyAndChunks() = runTest {
        val inner = object : ResearchModels {
            override suspend fun generator(deeper: Boolean) = object : TextGenerator {
                override fun generate(prompt: String, maxTokens: Int): Flow<String> = flowOf("a", "b")
                override fun generate(prompt: String, maxTokens: Int, onReady: () -> Unit): Flow<String> = flowOf("c", "d").onStart { onReady(); ready = true }
            }
            override suspend fun embedder(): PassageEmbedder? = null
        }
        val rec = PromptRecorder(inner)
        var fired = false
        val chunks = rec.generator(false).generate("p", 5) { fired = true }.toList()
        assertThat(fired).isTrue()
        assertThat(ready).isTrue()
        assertThat(chunks).containsExactly("c", "d").inOrder()
        assertThat(rec.toJson().getJSONObject(0).getString("output")).isEqualTo("cd")
    }

    private var ready = false

    @Test
    fun aFailedRecordKeepsThePromptsItHas() = runTest {
        val finder = RecordingFinder(PassageFinder { throw IllegalStateException("disk I/O error") })
        val prompts = PromptRecorder(Models(small, large))
        val out = File(tmp.root, "rf/results.jsonl")
        EvalRunner(ResearchPipeline(finder, prompts), finder, { it.sourceId }, prompts) { ticks += 10; ticks }.run(listOf(item("a")), out, GuessModels.NONE)
        val r = records(out).single()
        assertThat(r.getString("outcome")).isEqualTo("failed")
        assertThat(r.getJSONArray("prompts").getJSONObject(0).getString("stage")).isEqualTo("rewrite")
    }

    @Test
    fun resumesAfterLastRecordedItem() = runTest {
        val out = File(tmp.root, "r/results.jsonl").apply { parentFile!!.mkdirs(); writeText("{\"id\":\"a\",\"outcome\":\"answered\"}\n{\"id\":\"b\",\"outcome\":\"declined\"}\n") }
        runner().run(listOf(item("a"), item("b"), item("c")), out, GuessModels.NONE)
        val recs = records(out)
        assertThat(recs.map { it.getString("id") }).containsExactly("a", "b", "c").inOrder()
        assertThat(recs[0].has("runId")).isFalse() // a and b were left as they were
        assertThat(recs[2].getString("runId")).isEqualTo("r")
    }

    @Test
    fun halfWrittenLastLineIsIgnored() = runTest {
        val out = File(tmp.root, "r/results.jsonl").apply { parentFile!!.mkdirs(); writeText("{\"id\":\"a\",\"outcome\":\"answered\"}\n{\"id\":\"b\",\"outco") }
        runner().run(listOf(item("a"), item("b")), out, GuessModels.NONE)
        val recs = records(out)
        assertThat(recs.map { it.getString("id") }).containsExactly("a", "b").inOrder()
        assertThat(recs[1].getString("outcome")).isEqualTo("answered")
    }

    @Test
    fun followUpRunsPriorQuestionFirstAndPassesPriorTurn() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("f", question = "How hot is it?", prior = "At what temperature does water boil?")), out, GuessModels.NONE)
        val answerPrompts = small.prompts.filterNot { it.startsWith("Turn the question") }
        assertThat(answerPrompts).hasSize(2)
        assertThat(answerPrompts[0]).doesNotContain("Previous")
        assertThat(answerPrompts[1]).contains("Water boils at 100 °C") // the first answer is the prior turn
        assertThat(small.prompts.filter { it.startsWith("Turn the question") }[1]).contains("Previous question: At what temperature")
        assertThat(records(out)).hasSize(1)
    }

    @Test
    fun multiStepItemAlsoRunsThinkDeeperOnTheSamePool() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("m", multiStep = true)), out, GuessModels.NONE)
        val r = records(out).single()
        val deeper = r.getJSONObject("deeper")
        assertThat(deeper.getString("outcome")).isEqualTo("answered")
        assertThat(deeper.getJSONArray("chosen").getJSONObject(0).getString("text")).contains("100 degrees")
        assertThat(large.prompts).hasSize(1) // the large model answers; no second rewrite
        assertThat(large.prompts[0]).contains("100 degrees")
        assertThat(deeper.has("ms")).isTrue()
    }

    @Test
    fun declinedItemGetsRequestedGuesses() = runTest {
        val declining = Gen("small") { if (it.contains("Passages:")) "NOT IN LIBRARY" else "Maybe 100 degrees." }
        val out = File(tmp.root, "r/results.jsonl")
        runner(small = declining).run(listOf(item("d")), out, GuessModels.BOTH)
        val r = records(out).single()
        assertThat(r.getString("outcome")).isEqualTo("declined")
        val g = r.getJSONObject("guesses")
        assertThat(g.getJSONObject("small").getString("text")).isNotEmpty()
        assertThat(g.getJSONObject("small").getBoolean("fellBack")).isFalse()
        assertThat(g.getJSONObject("large").getString("text")).isNotEmpty()
        assertThat(g.getJSONObject("large").has("ms")).isTrue()

        val only = File(tmp.root, "r2/results.jsonl")
        runner(small = declining).run(listOf(item("d")), only, GuessModels.SMALL)
        val g2 = records(only).single().getJSONObject("guesses")
        assertThat(g2.has("small")).isTrue()
        assertThat(g2.has("large")).isFalse()
    }

    @Test
    fun aModelDeclinedRecordKeepsItsPassagesAndReply() = runTest {
        val declining = Gen("small") { if (it.contains("Passages:")) "NOT IN LIBRARY" else "x" }
        val out = File(tmp.root, "r/results.jsonl")
        runner(small = declining).run(listOf(item("d")), out, GuessModels.NONE)
        val r = records(out).single()
        assertThat(r.getString("outcome")).isEqualTo("declined")
        assertThat(r.getString("reply")).isEqualTo("NOT IN LIBRARY")
        assertThat(r.getJSONArray("chosen").length()).isGreaterThan(0)
    }

    @Test
    fun answeredItemGetsNoGuesses() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("a")), out, GuessModels.BOTH)
        assertThat(records(out).single().has("guesses")).isFalse()
    }

    @Test
    fun foundIsCappedAt60() = runTest {
        val many = (1..80).map { Passage(SourceKind.BOOK, "s$it", "T$it", location = "l$it", text = "water boils $it") }
        val out = File(tmp.root, "r/results.jsonl")
        runner(results = many).run(listOf(item("a")), out, GuessModels.NONE)
        assertThat(records(out).single().getJSONArray("found").length()).isEqualTo(60)
    }

    @Test
    fun articleSourceIsTheZimNameNotTheUuid() = runTest {
        val article = Passage(SourceKind.ARTICLE, "uuid-1234", "Water", location = "Water", text = "Water boils at 100 degrees.")
        val out = File(tmp.root, "r/results.jsonl")
        runner(results = listOf(article), source = { if (it.kind == SourceKind.ARTICLE) "wikipedia_en_top" else it.sourceId })
            .run(listOf(item("a")), out, GuessModels.NONE)
        val r = records(out).single()
        assertThat(r.getJSONArray("found").getJSONObject(0).getString("source")).isEqualTo("wikipedia_en_top")
        assertThat(r.getJSONArray("chosen").getJSONObject(0).getString("source")).isEqualTo("wikipedia_en_top")
    }

    @Test
    fun parsesDatasetAndFiltersByIdsAndGroups() {
        val text = """[{"id":"a","group":"x","answerable":true,"medical":false,"multiStep":true,"question":"q","priorQuestion":null,"answers":[]},
            {"id":"b","group":"y","answerable":false,"medical":true,"multiStep":false,"question":"q2","priorQuestion":"p"}]"""
        val items = EvalJson.parseDataset(text)
        assertThat(items.map { it.id }).containsExactly("a", "b")
        assertThat(items[0].priorQuestion).isNull()
        assertThat(items[1].priorQuestion).isEqualTo("p")
        assertThat(EvalJson.filter(items, "b", null).map { it.id }).containsExactly("b")
        assertThat(EvalJson.filter(items, null, "x,z").map { it.id }).containsExactly("a")
        assertThat(EvalJson.filter(items, "", "").map { it.id }).containsExactly("a", "b")
        assertThat(GuessModels.parse("both")).isEqualTo(GuessModels.BOTH)
        assertThat(GuessModels.parse(null)).isEqualTo(GuessModels.NONE)
    }

    @Test
    fun datasetMedicalFlagIsNotPassedAsAHint() = runTest {
        val out = File(tmp.root, "r/results.jsonl")
        runner().run(listOf(item("m").copy(medical = true)), out, GuessModels.NONE)
        assertThat(records(out).single().getBoolean("medical")).isFalse()
    }

    @Test
    fun aFailedRecordNamesTheReasonAndException() = runTest {
        val finder = RecordingFinder(PassageFinder { throw IllegalStateException("disk I/O error") })
        val runner = EvalRunner(ResearchPipeline(finder, Models(small, large)), finder, { it.sourceId }) { ticks += 10; ticks }
        val out = File(tmp.root, "rf/results.jsonl")
        runner.run(listOf(item("a")), out, GuessModels.NONE)
        val r = records(out).single()
        assertThat(r.getString("outcome")).isEqualTo("failed")
        assertThat(r.getString("reply")).isEqualTo("SEARCH_FAILED: IllegalStateException: disk I/O error")
    }

    @Test
    fun failedRecordsAreRetriedOnResume() = runTest {
        val out = File(tmp.root, "r/results.jsonl").apply { parentFile!!.mkdirs(); writeText("{\"id\":\"a\",\"outcome\":\"answered\"}\n{\"id\":\"b\",\"outcome\":\"failed\"}\n") }
        runner().run(listOf(item("a"), item("b")), out, GuessModels.NONE)
        val recs = records(out)
        assertThat(recs.map { it.getString("id") }).containsExactly("a", "b").inOrder()
        assertThat(recs[1].getString("outcome")).isEqualTo("answered")
        assertThat(File(out.parentFile, "results.jsonl.tmp").exists()).isFalse()
    }

    @Test
    fun anErrorAtTheTopWritesAFailedMarker() = runTest {
        val log = mutableListOf<String>()
        EvalSession.guarded(tmp.root, "r9", { log += it }) { throw NoSuchFieldError("boom") }
        assertThat(File(tmp.root, "r9/failed").readText()).contains("boom")
        assertThat(log.single()).contains("failed")
    }

    @Test
    fun missingDatasetFailsWithAReadableMessage() = runTest {
        EvalSession.guarded(tmp.root, "r8", {}) { EvalSession.loadItems(tmp.root, null, null) }
        assertThat(File(tmp.root, "r8/failed").readText()).contains("no dataset at")
    }

    @Test
    fun cancellationIsRethrownAndLeavesNoMarker() = runTest {
        val thrown = runCatching {
            EvalSession.guarded(tmp.root, "r7", {}) { throw kotlin.coroutines.cancellation.CancellationException("stop") }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(kotlin.coroutines.cancellation.CancellationException::class.java)
        assertThat(File(tmp.root, "r7/failed").exists()).isFalse()
    }
}
