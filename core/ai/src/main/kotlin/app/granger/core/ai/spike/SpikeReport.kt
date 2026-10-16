package app.granger.core.ai.spike

import java.util.Locale

object SpikeReport {
    fun toMarkdown(header: String, rows: List<SpikeMeasurement>): String = buildString {
        appendLine("## $header")
        appendLine()
        appendLine("| Backend | Step | Time (ms) | Peak PSS (MB) | TTFT (ms) | Chunks/s | Error |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        rows.forEach { r ->
            val rate = r.chunksPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: DASH
            appendLine(
                "| ${r.backend} | ${r.step} | ${r.millis.orDash()} | ${r.peakPssMb.orDash()} | " +
                    "${r.ttftMs.orDash()} | $rate | ${r.error?.replace("|", "\\|") ?: ""} |",
            )
        }
    }

    private const val DASH = "—"
    private fun Any?.orDash() = this?.toString() ?: DASH
}
