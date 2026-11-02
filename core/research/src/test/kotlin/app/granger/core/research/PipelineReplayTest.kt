package app.granger.core.research

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * Plan R2 Task 4: the app's own ranking replayed on the Mac, from a phone run's recorded searches.
 *
 * The real [ResearchPipeline], configured as `GrangerApp` builds it, gets each question with the run's keyword passages
 * (`found.jsonl`), its dense hits (with their cosines) and the rewrite's recorded keywords and medical flag. A fake model
 * captures the answer prompt. It reports how many prompts match the recorded ones (the replay's fidelity) and, for each
 * answerable item, whether an answer quote is in the prompt: recall by quote, not by article or chapter (Task 5).
 *
 * Runs only when asked: `GRANGER_REPLAY=r5-t7-all ./gradlew :core:research:test --tests '*PipelineReplayTest'`.
 * Writes `build/replay/<run>.tsv` (id, answerable, quote in the prompt, passages as recorded). Tuning runs only.
 */
class PipelineReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun replay() = runTest(timeout = kotlin.time.Duration.parse("30m")) {
        val run = System.getenv("GRANGER_REPLAY")
        assumeTrue("set GRANGER_REPLAY to a run under tools/eval/runs", run != null)
        val dir = File("../../tools/eval/runs/$run")
        val items = json.parseToJsonElement(File(dir, "dataset.json").readText()).let { e ->
            (if (e is JsonObject) e["items"]!! else e).jsonArray.map { it.jsonObject }
        }.associateBy { it.str("id")!! }
        check(items.values.none { it.str("split")?.startsWith("heldout") == true }) { "replay is for tuning runs only" }
        val found = File(dir, "found.jsonl").readLines().filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }.associateBy { it.str("id")!! }
        val records = File(dir, "results.jsonl").readLines().filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
        val fiction = json.parseToJsonElement(File("../../catalog/catalog.json").readText()).jsonObject["books"]!!.jsonArray
            .map { it.jsonObject }.filter { it.str("domain") == "literature" }.mapTo(HashSet()) { "book:granger:" + it.str("id") }

        val out = StringBuilder("id\tanswerable\tquoteInPrompt\tsameAsRecorded\tpassages\n")
        var same = 0
        var compared = 0
        var quoted = 0
        var answerable = 0
        for (rec in records) {
            val id = rec.str("id")!!
            val item = items[id] ?: continue
            val f = found[id] ?: continue
            val keywords = rec["keywords"]!!.jsonArray.map { it.jsonPrimitive.content }
            val medical = rec["medical"]?.jsonPrimitive?.booleanOrNull == true
            val answerPrompts = mutableListOf<String>()
            val generator = object : TextGenerator {
                override fun generate(prompt: String, maxTokens: Int) =
                    if (prompt.startsWith("Turn the question")) {
                        flowOf("keywords: ${keywords.joinToString(", ")}\nmedical: ${if (medical) "yes" else "no"}")
                    } else {
                        answerPrompts += prompt
                        flowOf(AnswerPrompt.DECLINE_TOKEN)
                    }
            }
            val models = object : ResearchModels {
                override suspend fun generator() = generator
                override suspend fun embedder() = PassageEmbedder { texts, _ -> texts.map { floatArrayOf(1f) } }
            }
            val passages = f["passages"]!!.jsonArray.map { passage(it.jsonObject) }
            val dense = f["dense"]?.jsonArray?.map { passage(it.jsonObject) }.orEmpty()
            val pipeline = ResearchPipeline(
                PassageFinder { passages }, models, RankingConfig(),
                sourceWeight = FictionWeight({ fiction }), dense = DenseFinder { dense },
                reserveNamed = true, yearFacts = true,
            )
            val events = pipeline.run(ResearchRequest(item.str("question")!!, priorQuestion = item.str("priorQuestion"))).toList()
            val prompt = answerPrompts.lastOrNull().orEmpty()
            // The prompt's rules change between runs, and recorded passages carry no author or date, so fidelity is
            // the passages chosen, in order.
            val chosen = ((events.last() as ResearchEvent.Done).outcome as? Outcome.Declined)?.passages.orEmpty().map { Ranking.textKey(it) }
            val recordedChosen = rec["chosen"]?.jsonArray?.map { Ranking.textKey(passage(it.jsonObject)) }.orEmpty()
            val isSame = chosen == recordedChosen
            compared++
            if (isSame) same++
            val isAnswerable = item["answerable"]!!.jsonPrimitive.boolean
            val quotes = item["answers"]?.jsonArray?.mapNotNull { it.jsonObject.str("quote")?.takeIf(String::isNotBlank) }.orEmpty()
            val hasQuote = quotes.any { fold(it) in fold(prompt) }
            if (isAnswerable && quotes.isNotEmpty()) { answerable++; if (hasQuote) quoted++ }
            out.append("$id\t$isAnswerable\t$hasQuote\t$isSame\t${chosen.size}\n")
        }
        File("build/replay").mkdirs()
        File("build/replay/$run.tsv").writeText(out.toString())
        println("REPLAY $run: passages as recorded $same/$compared; answer quote in the prompt $quoted/$answerable")
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun passage(o: JsonObject) = Passage(
        SourceKind.valueOf(o.str("kind")!!), o.str("source").orEmpty(), o.str("title").orEmpty(),
        location = o.str("location").orEmpty(), heading = o.str("heading").orEmpty(), text = o.str("text").orEmpty(),
        cosine = o["cosine"]?.jsonPrimitive?.doubleOrNull,
    )

    private val marks = Regex("\\p{M}+")
    private val words = Regex("[\\p{L}\\p{N}_]+")

    /** Words only, as `metrics.fold_words` folds them: accents, case, punctuation and spacing. */
    private fun fold(s: String) =
        " " + words.findAll(marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase().replace('’', '\'')).joinToString(" ") { it.value } + " "
}
