package app.granger.home

import androidx.compose.ui.res.stringResource
import app.granger.R
import app.granger.UiText
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
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
import app.granger.theme.status

/** How solid a domain's dot is (spec §5): None faint, Basic half, Comprehensive full. */
fun dotAlpha(coverage: Coverage): Float = when (coverage) {
    Coverage.NONE -> 0.25f
    Coverage.BASIC -> 0.6f
    Coverage.COMPREHENSIVE -> 1f
}

/** The strip's one sentence: the first signal, else why there is none. */
fun stripText(report: ReadinessReport): UiText = report.signals.firstOrNull()?.text
    ?: UiText.Res(if (report.needsCatalogRefresh) R.string.readiness_refresh_catalog else R.string.readiness_ready)

/**
 * The command-line status voice (brand brief §8; Plan T1, Task 5): what Granger holds on this phone, which all works
 * with no signal. Null with nothing held. The phone's own media is referenced, not held, so it doesn't count.
 */
fun stripStatus(report: ReadinessReport): UiText? {
    val held = report.storage.filterNot { it.referencedOnly }.sumOf { it.bytes }
    return if (held > 0) UiText.Res(R.string.home_strip_status, formatBytes(held)) else null
}

/** The status line, nine dots coloured by status and one suggestion sentence; the whole strip opens Readiness. */
@Composable
fun ReadinessStrip(report: ReadinessReport, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val readiness = stringResource(R.string.readiness_title)
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = readiness }
            .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stripStatus(report)?.let { status ->
            Text(status.resolve(), style = MaterialTheme.typography.labelMedium.status, color = GrangerTheme.extras.status)
        }
        if (report.domains.isNotEmpty()) {
            // The dots say what they count (Neal, 2026-11-08: nine unlabelled dots).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (domain in report.domains) {
                    Box(
                        Modifier
                            .testTag("strip-dot-${domain.id}")
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha(domain.coverage))),
                    )
                }
                Text(
                    stringResource(R.string.home_strip_areas, coveredAreas(report), report.domains.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).testTag("strip-areas"),
                )
            }
        }
        // The suggestion leads somewhere: a chevron says so.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stripText(report).resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The areas with anything on the phone for them (Basic or Comprehensive). */
fun coveredAreas(report: ReadinessReport): Int = report.domains.count { it.coverage != Coverage.NONE }
