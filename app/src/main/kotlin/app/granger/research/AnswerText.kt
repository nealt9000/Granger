package app.granger.research

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.em
import app.granger.theme.GrangerColors

/** An answer split into its words and its `[n]` citations. */
object AnswerText {
    sealed interface Segment {
        data class Text(val text: String) : Segment

        /** Passage [n], counted from 1. */
        data class Cite(val n: Int) : Segment
    }

    /** `[1]`, `[2, 3]`: one or more passage numbers in square brackets. */
    private val CITATION = Regex("""\[(\d+(?:\s*,\s*\d+)*)]""")

    fun segments(answer: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var from = 0
        for (match in CITATION.findAll(answer)) {
            if (match.range.first > from) out += Segment.Text(answer.substring(from, match.range.first))
            match.groupValues[1].split(',').forEach { out += Segment.Cite(it.trim().toInt()) }
            from = match.range.last + 1
        }
        if (from < answer.length) out += Segment.Text(answer.substring(from))
        return out
    }
}

/** [answer] with each citation drawn as a small raised flame chip; tapping one calls [onCite] with its number. */
@Composable
internal fun AnswerBody(answer: String, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cite by rememberUpdatedState(onCite)
    val text = remember(answer) {
        val chip = SpanStyle(
            color = GrangerColors.Flame,
            background = GrangerColors.SurfaceHigh,
            fontWeight = FontWeight.SemiBold,
            fontSize = 0.75.em,
            baselineShift = BaselineShift.Superscript,
        )
        buildAnnotatedString {
            AnswerText.segments(answer).forEach { segment ->
                when (segment) {
                    is AnswerText.Segment.Text -> append(segment.text)
                    is AnswerText.Segment.Cite -> {
                        val link = LinkAnnotation.Clickable("cite:${segment.n}", TextLinkStyles(chip)) { cite(segment.n) }
                        withLink(link) { append(" ${segment.n} ") }
                    }
                }
            }
        }
    }
    Text(text, modifier, style = MaterialTheme.typography.bodyLarge, color = GrangerColors.OnSurface)
}
