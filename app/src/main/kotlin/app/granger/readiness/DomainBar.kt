package app.granger.readiness

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A domain's bar (spec §4): installed bytes over the comprehensive tier's size, with a tick at the essential tier's size.
 * Comprehensive fills the bar whatever the bytes say (Decision 7).
 */
@Composable
fun DomainBar(installedBytes: Long, essentialBytes: Long, comprehensiveBytes: Long, coverage: Coverage, modifier: Modifier = Modifier) {
    val fill = when {
        coverage == Coverage.COMPREHENSIVE -> 1f
        comprehensiveBytes <= 0L -> if (installedBytes > 0L) 1f else 0f
        else -> (installedBytes.toFloat() / comprehensiveBytes).coerceIn(0f, 1f)
    }
    val tick = if (comprehensiveBytes > 0L) (essentialBytes.toFloat() / comprehensiveBytes).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (fill > 0f) Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        if (tick > 0.01f && tick < 0.99f) {
            Box(Modifier.offset(x = maxWidth * tick).width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
