package app.granger.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.granger.readiness.Coverage
import app.granger.formatBytes
import app.granger.readiness.ReadinessReport
import app.granger.theme.GrangerTheme

/** How solid a domain's dot is (spec §5): None faint, Basic half, Comprehensive full. */
fun dotAlpha(coverage: Coverage): Float = when (coverage) {
    Coverage.NONE -> 0.25f
    Coverage.BASIC -> 0.6f
    Coverage.COMPREHENSIVE -> 1f
}

/** The strip's one sentence: the first signal, else why there is none. */
fun stripText(report: ReadinessReport): String = report.signals.firstOrNull()?.text
    ?: if (report.needsCatalogRefresh) "Refresh the catalog to see your coverage." else "Ready."

/**
 * The command-line status voice (brand brief §8; Plan T1, Task 5): what Granger holds on this phone, which all works
 * with no signal. Null with nothing held. The phone's own media is referenced, not held, so it doesn't count.
 */
fun stripStatus(report: ReadinessReport): String? {
    val held = report.storage.filterNot { it.referencedOnly }.sumOf { it.bytes }
    return if (held > 0) "> ${formatBytes(held)} here, ready offline." else null
}

/** The status line, nine dots coloured by status and one suggestion sentence; the whole strip opens Readiness. */
@Composable
fun ReadinessStrip(report: ReadinessReport, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Readiness" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stripStatus(report)?.let { status ->
            Text(status, style = MaterialTheme.typography.labelMedium, color = GrangerTheme.extras.status)
        }
        if (report.domains.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (domain in report.domains) {
                    Box(
                        Modifier
                            .testTag("strip-dot-${domain.id}")
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha(domain.coverage))),
                    )
                }
            }
        }
        Text(stripText(report), style = MaterialTheme.typography.bodyMedium)
    }
}
