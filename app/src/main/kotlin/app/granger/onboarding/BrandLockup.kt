package app.granger.onboarding

import androidx.compose.foundation.Image
import app.granger.theme.GrangerTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R

/**
 * The brand (brand brief §8): the mark, the tape whose right half is the G resting on two books, in lantern over GRANGER in the
 * stencil. Both are vectors from `tools/brand/make_drawables.py`, drawn white and tinted here.
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, markWidth: Dp = 128.dp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.drawable.granger_mark),
            contentDescription = null,
            modifier = Modifier.width(markWidth).aspectRatio(MARK_ASPECT),
            colorFilter = ColorFilter.tint(GrangerTheme.extras.mark),
        )
        Spacer(Modifier.height(markWidth * 0.16f))
        Image(
            painterResource(R.drawable.granger_wordmark),
            contentDescription = "Granger",
            modifier = Modifier.width(markWidth * 1.25f).aspectRatio(WORDMARK_ASPECT),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
    }
}

// The drawables' viewports (tools/brand/make_drawables.py prints them).
private const val MARK_ASPECT = 980f / 913f
private const val WORDMARK_ASPECT = 7132f / 1636f
