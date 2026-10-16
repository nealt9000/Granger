package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationTimingTest {
    @Test fun measuresTimeToFirstChunkAndDecodeRate() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, listOf(500, 100, 100))

        val r = timeGeneration(llm, "q", maxChunks = 100, clock = clock)

        assertThat(r.ttftMs).isEqualTo(500)
        assertThat(r.chunks).isEqualTo(3)
        assertThat(r.decodeMs).isEqualTo(200)
        assertThat(r.chunksPerSecond).isWithin(0.001).of(10.0)
        assertThat(r.text).isEqualTo("t0 t1 t2 ")
    }

    @Test fun stopsAtMaxChunks() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, List(10) { 10L })

        val r = timeGeneration(llm, "q", maxChunks = 3, clock = clock)

        assertThat(r.chunks).isEqualTo(3)
    }

    @Test fun emptyStream() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, emptyList())

        val r = timeGeneration(llm, "q", maxChunks = 10, clock = clock)

        assertThat(r.ttftMs).isNull()
        assertThat(r.chunks).isEqualTo(0)
        assertThat(r.chunksPerSecond).isEqualTo(0.0)
    }

    @Test fun singleChunkHasZeroRateNotInfinity() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, listOf(300))

        val r = timeGeneration(llm, "q", maxChunks = 10, clock = clock)

        assertThat(r.ttftMs).isEqualTo(300)
        assertThat(r.chunksPerSecond).isEqualTo(0.0)
    }
}
