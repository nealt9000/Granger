package app.granger.core.ai.spike

import java.util.Locale

object SpikeReport {
    fun toMarkdown(header: String, rows: List<SpikeMeasurement>): String = buildString {
        appendLine("## $header")
        appendLine()
        appendLine("| Backend | Step | Time (ms) | Peak RSS (MB) | TTFT (ms) | Chunks/s | Error |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        rows.forEach { appendLine(row(it)) }
    }

    /** One table row, so a run can append rows to disk as they are measured. */
    fun row(r: SpikeMeasurement): String {
        val rate = r.chunksPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: DASH
        return "| ${r.backend} | ${r.step} | ${r.millis.orDash()} | ${r.peakRssMb.orDash()} | " +
            "${r.ttftMs.orDash()} | $rate | ${r.error?.replace("|", "\\|") ?: ""} |"
    }

    private const val DASH = "—"
    private fun Any?.orDash() = this?.toString() ?: DASH
}
