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
    private val models = SpikeModels(small = "/m/small", large = "/m/large", embedder = "/m/emb")
    private val config = SpikeConfig(LlmBackend.CPU, models)

    private fun runner(
        clock: FakeClock,
        llms: FakeLlmFactory,
        embedder: FakeEmbedder = FakeEmbedder(),
        fileExists: (String) -> Boolean = { true },
        memory: FakeMemory? = null,
    ) = SpikeRunner(
        llmFactory = llms,
        embedderFactory = { _ -> embedder },
        memory = memory ?: FakeMemory(),
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
        assertThat(answer.peakRssMb).isEqualTo(1234)
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
        val llms = FakeLlmFactory(clock, failLoadFor = mapOf("/m/large" to IllegalStateException("load failed: /m/large")))

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

    @Test fun stepTimeExcludesSamplerOverhead() = runTest {
        val clock = FakeClock()
        val rows = runner(clock, FakeLlmFactory(clock), memory = FakeMemory(clock = clock, overheadMs = 1000))
            .run(config).associateBy { it.step }

        assertThat(rows.getValue("load small").millis).isEqualTo(0)
        assertThat(rows.getValue("answer (small)").millis).isEqualTo(550)
    }

    @Test fun errorsAreRecordedAndRunContinues() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock, failLoadFor = mapOf("/m/small" to UnsatisfiedLinkError("no liblitertlm")))

        val rows = runner(clock, llms).run(config)

        assertThat(rows.map { it.step }).containsExactlyElementsIn(SpikeRunner.STEPS).inOrder()
        assertThat(rows.first().error).isEqualTo("no liblitertlm")
        assertThat(llms.openEngines).isEmpty()
    }

    @Test fun rowsAreReportedAsTheyAreMeasured() = runTest {
        val clock = FakeClock()
        val seen = mutableListOf<SpikeMeasurement>()

        val rows = runner(clock, FakeLlmFactory(clock)).run(config, onRow = { seen += it })

        assertThat(seen).isEqualTo(rows)
    }

    @Test fun thinkDeeperUsesEightPassages() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)

        runner(clock, llms).run(config)

        assertThat(llms.byPath.getValue("/m/large").flatMap { it.prompts }).containsExactly(SpikePrompts.ANSWER_DEEP)
        assertThat(SpikePrompts.ANSWER_DEEP).contains("[8]")
    }

    @Test fun answerStepsPassTheChunkCapAsTokenLimit() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)

        runner(clock, llms).run(config.copy(maxChunks = 64))

        assertThat(llms.byPath.getValue("/m/small").mapNotNull { it.lastMaxTokens }.toSet()).containsExactly(64)
    }
}
