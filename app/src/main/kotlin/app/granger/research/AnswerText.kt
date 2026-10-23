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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import app.granger.theme.GrangerColors

/** An answer split into its words and its `[n]` citations; `*italic*` and `**bold**` become styles, other Markdown stays as typed. */
object AnswerText {
    sealed interface Segment {
        data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false) : Segment

        /** Passage [n], counted from 1. [bold] and [italic] carry the style it sits in, so a chip inside italics keeps it. */
        data class Cite(val n: Int, val bold: Boolean = false, val italic: Boolean = false) : Segment
    }

    /** `[1]`, `[2, 3]`: one or more passage numbers in square brackets. */
    private val CITATION = Regex("""\[(\d+(?:\s*,\s*\d+)*)]""")

    /** The banner over a medical answer; spoken first when an answer is read aloud. */
    const val MEDICAL = "Not medical advice. If you can reach emergency services or a professional, do so."

    /** The banner over an answer from a weak match in the library (Plan R1); spoken first too, after [MEDICAL]. */
    const val WEAK = "Your library only loosely matches this. I can't be sure, but if I had to guess:"

    /** What is read before an answer: [MEDICAL] and [WEAK] as they apply, or null. */
    fun preface(medical: Boolean, weak: Boolean): String? =
        listOfNotNull(MEDICAL.takeIf { medical }, WEAK.takeIf { weak }).joinToString("\n\n").ifEmpty { null }

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val LIST_MARK = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
    private val SPACE_BEFORE_PUNCTUATION = Regex(""" +([.,;:!?])""")

    /**
     * [answer] as the voice reads it (Plan V2): no citation chips, no emphasis marks, headings and list items as
     * paragraphs of their own, and the [preface] first. Numbers stay as written.
     */
    fun spoken(answer: String, medical: Boolean = false, weak: Boolean = false): String {
        val plain = segments(answer).filterIsInstance<Segment.Text>().joinToString("") { it.text }
        val lines = plain.lines().map { line ->
            when {
                HEADING.containsMatchIn(line) -> "\n" + HEADING.replace(line, "").trim() + "\n"
                LIST_MARK.containsMatchIn(line) -> "\n" + LIST_MARK.replace(line, "").trim()
                else -> line.trim()
            }
        }
        val body = lines.joinToString("\n")
            .replace(SPACE_BEFORE_PUNCTUATION, "$1")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return preface(medical, weak)?.let { "$it\n\n$body" } ?: body
    }

    /** [answer] without its emphasis marks, and the style of each remaining character. */
    private class Marked(val text: String, val bold: BooleanArray, val italic: BooleanArray)

    /** A mark opens only before a non-space and with a closing mark later on the same line; it closes after a non-space. */
    private fun mark(answer: String): Marked {
        val out = StringBuilder()
        val bold = ArrayList<Boolean>()
        val italic = ArrayList<Boolean>()
        var b = false
        var i = false
        var k = 0
        fun closes(at: Int, mark: String): Boolean {
            var j = at
            while (j < answer.length && answer[j] != '\n') {
                if (answer.startsWith(mark, j) && j > at && !answer[j - 1].isWhitespace() &&
                    (mark.length == 2 || !answer.startsWith("**", j))
                ) return true
                j++
            }
            return false
        }
        while (k < answer.length) {
            val c = answer[k]
            if (c == '*') {
                val double = answer.startsWith("**", k)
                val mark = if (double) "**" else "*"
                val after = k + mark.length
                val canClose = (if (double) b else i) && k > 0 && !answer[k - 1].isWhitespace()
                val canOpen = !(if (double) b else i) && after < answer.length && !answer[after].isWhitespace() &&
                    answer[after] != '*' && closes(after, mark)
                if (canClose || canOpen) {
                    if (double) b = !b else i = !i
                    k = after
                    continue
                }
            }
            out.append(c); bold += b; italic += i
            k++
        }
        return Marked(out.toString(), bold.toBooleanArray(), italic.toBooleanArray())
    }

    fun segments(answer: String): List<Segment> {
        val marked = mark(answer)
        val text = marked.text
        val out = mutableListOf<Segment>()
        /** Text over [from, to), cut where the style changes. */
        fun addText(from: Int, to: Int) {
            var s = from
            while (s < to) {
                var e = s + 1
                while (e < to && marked.bold[e] == marked.bold[s] && marked.italic[e] == marked.italic[s]) e++
                out += Segment.Text(text.substring(s, e), marked.bold[s], marked.italic[s])
                s = e
            }
        }
        var from = 0
        for (match in CITATION.findAll(text)) {
            // A number too long for an Int (an ISBN in brackets) is not a citation; the bracket stays text.
            val numbers = match.groupValues[1].split(',').map { it.trim().toIntOrNull() }
            if (numbers.any { it == null }) continue
            addText(from, match.range.first)
            val at = match.range.first
            numbers.forEach { out += Segment.Cite(it!!, marked.bold[at], marked.italic[at]) }
            from = match.range.last + 1
        }
        addText(from, text.length)
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
        fun style(bold: Boolean, italic: Boolean) = SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
        )
        buildAnnotatedString {
            AnswerText.segments(answer).forEach { segment ->
                when (segment) {
                    is AnswerText.Segment.Text -> withStyle(style(segment.bold, segment.italic)) { append(segment.text) }
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
