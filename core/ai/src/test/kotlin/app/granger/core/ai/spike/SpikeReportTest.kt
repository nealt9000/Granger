package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpikeReportTest {
    @Test fun formatsSuccessAndErrorRows() {
        val md = SpikeReport.toMarkdown(
            "Pixel 6",
            listOf(
                SpikeMeasurement(LlmBackend.GPU, "answer (small)", millis = 4200, peakPssMb = 2100, ttftMs = 900, chunksPerSecond = 21.456),
                SpikeMeasurement(LlmBackend.GPU, "swap small->large", error = "load failed"),
            ),
        )

        assertThat(md).isEqualTo(
            """
            |## Pixel 6
            |
            || Backend | Step | Time (ms) | Peak PSS (MB) | TTFT (ms) | Chunks/s | Error |
            || --- | --- | --- | --- | --- | --- | --- |
            || GPU | answer (small) | 4200 | 2100 | 900 | 21.5 |  |
            || GPU | swap small->large | — | — | — | — | load failed |
            |
            """.trimMargin(),
        )
    }

    @Test fun escapesPipes() {
        val md = SpikeReport.toMarkdown(
            "h",
            listOf(SpikeMeasurement(LlmBackend.CPU, "load small", error = "a|b")),
        )

        assertThat(md).contains("| a\\|b |")
    }
}
