package app.granger.theme

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line that shrinks, down to [minSize], rather than break a word or cut it (Plan L2 Task 4): a label with a fixed
 * slot, such as a top bar's title beside its actions or a tab, where Spanish runs longer than English. At its usual
 * size whenever it fits, so English is unchanged.
 */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, minSize: TextUnit = 12.sp) {
    val size = LocalTextStyle.current.fontSize
    Text(
        text,
        modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = if (size.isSp && size.value > minSize.value) TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = size, stepSize = 0.5.sp) else null,
    )
}
