package app.granger.eval

import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.MemorySampler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class PromptReplayTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Engine(val reply: (String) -> List<String>) : LlmEngine {
        var loads = 0
        override fun load() { loads++ }
        override fun generate(prompt: String, maxTokens: Int): Flow<String> = flowOf(*reply(prompt).toTypedArray())
        override fun close() = Unit
    }

    private object NoMemory : MemorySampler {
        override suspend fun <T> peakRssMb(block: suspend () -> T): Pair<T, Int> = block() to 42
    }

    private fun prompts(vararg lines: Pair<String, String>) = tmp.newFile().apply {
        writeText(lines.joinToString("") { (id, p) -> JSONObject().put("id", id).put("prompt", p).put("passages", 2).toString() + "\n" })
    }

    private fun records(out: java.io.File) = out.readLines().map(::JSONObject)

    @Test
    fun classifiesEachReplyAsThePipelineDoes() = runTest {
        val engine = Engine {
            when (it) {
                "a" -> listOf("Water boils ", "at 100 °C [1].")
                "d" -> listOf("NOT IN LIBRARY")
                else -> listOf("It cites [3].")
            }
        }
        val out = tmp.root.resolve("replay.jsonl")
        var t = 0L
        PromptReplay({ _, _ -> engine }, NoMemory) { t += 10; t }.run(prompts("a" to "a", "d" to "d", "u" to "u"), "m", LlmBackend.GPU, out)
        val r = records(out)
        assertThat(r.map { it.getString("outcome") }).containsExactly("answered", "declined", "unverified").inOrder()
        assertThat(r[0].getJSONArray("cited").getInt(0)).isEqualTo(1)
        assertThat(r[0].has("loadMs")).isTrue()
        assertThat(r[1].has("loadMs")).isFalse()
        assertThat(r[0].getInt("peakRssMb")).isEqualTo(42)
    }

    @Test
    fun skipsIdsAlreadyWrittenAndLoadsNothingWhenDone() = runTest {
        val engine = Engine { listOf("Yes [1].") }
        val out = tmp.root.resolve("replay.jsonl").apply { writeText(JSONObject().put("id", "a").toString() + "\n") }
        val replay = PromptReplay({ _, _ -> engine }, NoMemory) { 0L }
        replay.run(prompts("a" to "a", "b" to "b"), "m", LlmBackend.GPU, out)
        assertThat(records(out).map { it.getString("id") }).containsExactly("a", "b").inOrder()
        replay.run(prompts("a" to "a", "b" to "b"), "m", LlmBackend.GPU, out)
        assertThat(engine.loads).isEqualTo(1)
    }

    @Test
    fun thinkingIsNotPartOfTheAnswer() {
        assertThat(PromptReplay.withoutThinking("<think>\nhmm [9]\n</think>\n\nYes [1].")).isEqualTo("Yes [1].")
        assertThat(PromptReplay.withoutThinking("<think>still going")).isEmpty()
        assertThat(PromptReplay.withoutThinking("The prompt opened it.\n</think>\n\nYes [1].")).isEqualTo("Yes [1].")
        assertThat(PromptReplay.withoutThinking("Yes [1].")).isEqualTo("Yes [1].")
    }
}
