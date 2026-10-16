package app.granger.core.ai.spike

import app.granger.core.ai.FakeClock
import app.granger.core.ai.FakeEmbedder
import app.granger.core.ai.FakeLlmFactory
import app.granger.core.ai.FakeMemory
import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpikeRunnerTest {
    private val models = SpikeModels(small = "/m/small", large = "/m/large", embedder = "/m/emb", embedderTokenizer = "/m/spm")
    private val config = SpikeConfig(LlmBackend.CPU, models)

    private fun runner(
        clock: FakeClock,
        llms: FakeLlmFactory,
        embedder: FakeEmbedder = FakeEmbedder(),
        fileExists: (String) -> Boolean = { true },
    ) = SpikeRunner(
        llmFactory = llms,
        embedderFactory = { _, _ -> embedder },
        memory = FakeMemory(),
        clock = clock,
        fileExists = fileExists,
    )

    @Test fun runsEveryStepInOrder() = runTest {
        val clock = FakeClock()
        val rows = runner(clock, FakeLlmFactory(clock)).run(config)

        assertThat(rows.map { it.step }).containsExactlyElementsIn(SpikeRunner.STEPS).inOrder()
        assertThat(rows.all { it.error == null }).isTrue()
    }

    @Test fun generationStepsRecordTtftAndRate() = runTest {
        val clock = FakeClock()
        val rows = runner(clock, FakeLlmFactory(clock)).run(config)

        val answer = rows.single { it.step == "answer (small)" }
        assertThat(answer.ttftMs).isEqualTo(400)
        assertThat(answer.chunksPerSecond).isWithin(0.001).of(20.0)
        assertThat(answer.peakPssMb).isEqualTo(1234)
    }

    @Test fun neverHoldsTwoLlmsOpen() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)
        val embedder = FakeEmbedder()

        runner(clock, llms, embedder).run(config)

        assertThat(llms.maxOpen).isEqualTo(1)
        assertThat(llms.openEngines).isEmpty()
        assertThat(embedder.closed).isTrue()
    }

    @Test fun missingFilesStopEarly() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)

        val rows = runner(clock, llms, fileExists = { it != "/m/large" }).run(config)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().step).isEqualTo("check files")
        assertThat(rows.single().error).isEqualTo("missing: /m/large")
        assertThat(llms.events).isEmpty()
    }

    @Test fun loadFailureContinuesAndCleansUp() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock, failLoadFor = setOf("/m/large"))

        val rows = runner(clock, llms).run(config).associateBy { it.step }

        assertThat(rows.getValue("swap small->large").error).isEqualTo("load failed: /m/large")
        assertThat(rows.getValue("answer (large)").error).isEqualTo("model not loaded")
        assertThat(rows.getValue("swap large->small").error).isNull()
        assertThat(llms.openEngines).isEmpty()
        assertThat(llms.maxOpen).isEqualTo(1)
    }

    @Test fun emptyOutputIsAnError() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock, delaysMs = emptyList())

        val rows = runner(clock, llms).run(config).associateBy { it.step }

        assertThat(rows.getValue("answer (small)").error).isEqualTo("no output")
        assertThat(rows.getValue("answer (small)").ttftMs).isNull()
    }
}
