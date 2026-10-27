package app.granger.onboarding

import androidx.compose.foundation.Image
import app.granger.theme.GrangerTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R

/**
 * The brand (brand brief §8): the mark, the owl in the G with lantern eyes, over GRANGER in Spectral caps. All are vectors
 * from `tools/brand/make_drawables.py`, drawn white and tinted here; the mark is three layers (eyes, pupils, body).
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, markWidth: Dp = 128.dp) {
    val extras = GrangerTheme.extras
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(markWidth).aspectRatio(1f)) {
            MarkLayer(R.drawable.granger_mark_eyes, extras.markEyes)
            MarkLayer(R.drawable.granger_mark_pupils, extras.markPupils)
            MarkLayer(R.drawable.granger_mark, extras.mark)
        }
        Spacer(Modifier.height(markWidth * 0.16f))
        Image(
            painterResource(R.drawable.granger_wordmark),
            contentDescription = "Granger",
            modifier = Modifier.width(markWidth * 1.6f).aspectRatio(WORDMARK_ASPECT),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
    }
}

@Composable
private fun BoxScope.MarkLayer(res: Int, color: Color) = Image(
    painterResource(res),
    contentDescription = null,
    modifier = Modifier.matchParentSize(),
    colorFilter = ColorFilter.tint(color),
)

// The wordmark's viewport (tools/brand/make_drawables.py prints it); the mark's is square.
private const val WORDMARK_ASPECT = 5833f / 680f
