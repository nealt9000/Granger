package app.granger.research

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * An answer split into its words and its `[n]` citations; `*italic*` and `**bold**` become styles. Laid out ([blocks],
 * Plan U3c Decision 8), headings are bold lines, list items hang from their bullet or number, and `inline code` is
 * set apart; other Markdown stays as typed.
 */
object AnswerText {
    sealed interface Segment {
        /** [code]: inside backticks, which are dropped; only [blocks] looks for them. */
        data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false) : Segment

        /** Passage [n], counted from 1. [bold] and [italic] carry the style it sits in, so a chip inside italics keeps it. */
        data class Cite(val n: Int, val bold: Boolean = false, val italic: Boolean = false) : Segment
    }

    /** `[1]`, `[2, 3]`: one or more passage numbers in square brackets. */
    private val CITATION = Regex("""\[(\d+(?:\s*,\s*\d+)*)]""")

    /** The banner over a medical answer; spoken first when an answer is read aloud. */
    const val MEDICAL = "Not medical advice. If you can reach emergency services or a professional, do so."

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val LIST_MARK = Regex("""^\s*(?:[-*•]|\d+[.)])\s+""")
    private val BULLET = Regex("""^([ \t]*)[-*•+]\s+(.*)$""")
    private val NUMBERED = Regex("""^([ \t]*)(\d{1,3}[.)])\s+(.*)$""")

    /** One line of an answer as it's laid out. */
    sealed interface Block {
        val segments: List<Segment>

        /** A paragraph's line as typed; empty for a blank line between paragraphs. */
        data class Line(override val segments: List<Segment>) : Block

        /** A `#` heading, shown as a bold line. */
        data class Heading(override val segments: List<Segment>) : Block

        /** A list item: [marker] is "•" for any bullet, or the number as typed ("3."); [depth] its nesting, from 0. */
        data class Item(val marker: String, val depth: Int, override val segments: List<Segment>) : Block
    }

    /** [answer] line by line as headings, list items and plain lines, each with its styles, code and citations. */
    fun blocks(answer: String): List<Block> = answer.lines().map { line ->
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            HEADING.containsMatchIn(line) -> Block.Heading(segments(HEADING.replace(line, "").trimEnd(), code = true))
            numbered != null -> Block.Item(numbered.groupValues[2], depth(numbered.groupValues[1]), segments(numbered.groupValues[3], code = true))
            bullet != null -> Block.Item("•", depth(bullet.groupValues[1]), segments(bullet.groupValues[2], code = true))
            else -> Block.Line(segments(line, code = true))
        }
    }

    /** Two spaces (or a tab) a level, up to three. */
    private fun depth(indent: String): Int = (indent.fold(0) { n, c -> n + if (c == '\t') 2 else 1 } / 2).coerceAtMost(3)
    private val SPACE_BEFORE_PUNCTUATION = Regex(""" +([.,;:!?])""")

    /**
     * [answer] as the voice reads it (Plan V2): no citation chips, no emphasis marks, headings and list items as
     * paragraphs of their own, and [MEDICAL] first when [medical]. Numbers stay as written.
     */
    fun spoken(answer: String, medical: Boolean = false): String {
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
        return if (medical) "$MEDICAL\n\n$body" else body
    }

    /** [answer] without its emphasis marks (and its backticks), and the style of each remaining character. */
    private class Marked(val text: String, val bold: BooleanArray, val italic: BooleanArray, val code: BooleanArray)

    /**
     * A mark opens only before a non-space and with a closing mark later on the same line; it closes after a non-space.
     * With [code], a backtick with another later on the line opens a code span, taken as typed: no emphasis or
     * citations inside it.
     */
    private fun mark(answer: String, code: Boolean): Marked {
        val out = StringBuilder()
        val bold = ArrayList<Boolean>()
        val italic = ArrayList<Boolean>()
        val coded = ArrayList<Boolean>()
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
            if (code && c == '`') {
                val end = answer.indexOf('`', k + 1)
                if (end > k + 1 && answer.indexOf('\n', k + 1).let { it < 0 || it > end }) {
                    for (j in k + 1 until end) { out.append(answer[j]); bold += false; italic += false; coded += true }
                    k = end + 1
                    continue
                }
            }
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
            out.append(c); bold += b; italic += i; coded += false
            k++
        }
        return Marked(out.toString(), bold.toBooleanArray(), italic.toBooleanArray(), coded.toBooleanArray())
    }

    /** [answer]'s words and citations; with [code], `inline code` too (the voice keeps the backticks, as it always has). */
    fun segments(answer: String, code: Boolean = false): List<Segment> {
        val marked = mark(answer, code)
        val text = marked.text
        val out = mutableListOf<Segment>()
        /** Text over [from, to), cut where the style changes. */
        fun addText(from: Int, to: Int) {
            var s = from
            while (s < to) {
                var e = s + 1
                while (e < to && marked.bold[e] == marked.bold[s] && marked.italic[e] == marked.italic[s] && marked.code[e] == marked.code[s]) e++
                out += Segment.Text(text.substring(s, e), marked.bold[s], marked.italic[s], marked.code[s])
                s = e
            }
        }
        var from = 0
        for (match in CITATION.findAll(text)) {
            // A number too long for an Int (an ISBN in brackets) is not a citation; the bracket stays text.
            val numbers = match.groupValues[1].split(',').map { it.trim().toIntOrNull() }
            if (numbers.any { it == null }) continue
            // Brackets in code are code.
            if (match.range.any { marked.code[it] }) continue
            addText(from, match.range.first)
            val at = match.range.first
            numbers.forEach { out += Segment.Cite(it!!, marked.bold[at], marked.italic[at]) }
            from = match.range.last + 1
        }
        addText(from, text.length)
        return out
    }
}

/** How far each list level steps in, and the room a bullet or number hangs in before the item's lines. */
private const val LEVEL_EM = 1.25f
private const val HANG_EM = 1.6f

private fun markerId(marker: String) = "marker:$marker"

/**
 * [answer] laid out ([AnswerText.blocks]): headings bold, each list item a paragraph whose wrapped lines line up after
 * its marker (an inline placeholder [HANG_EM] wide, drawn by [AnswerBody]), code in [code], and each citation a [chip]
 * link calling [onCite]. Plain lines keep their newlines; an item's paragraph breaks the line itself.
 */
internal fun annotatedAnswer(answer: String, chip: SpanStyle, code: SpanStyle, onCite: (Int) -> Unit): AnnotatedString {
    fun style(segment: AnswerText.Segment.Text) = if (segment.code) code else SpanStyle(
        fontWeight = if (segment.bold) FontWeight.Bold else null,
        fontStyle = if (segment.italic) FontStyle.Italic else null,
    )
    return buildAnnotatedString {
        fun segments(of: List<AnswerText.Segment>) = of.forEach { segment ->
            when (segment) {
                is AnswerText.Segment.Text -> withStyle(style(segment)) { append(segment.text) }
                is AnswerText.Segment.Cite -> {
                    val link = LinkAnnotation.Clickable("cite:${segment.n}", TextLinkStyles(chip)) { onCite(segment.n) }
                    withLink(link) { append(" ${segment.n} ") }
                }
            }
        }
        var previous: AnswerText.Block? = null
        for (block in AnswerText.blocks(answer)) {
            if (previous != null && previous !is AnswerText.Block.Item && block !is AnswerText.Block.Item) append('\n')
            when (block) {
                is AnswerText.Block.Line -> segments(block.segments)
                is AnswerText.Block.Heading -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { segments(block.segments) }
                is AnswerText.Block.Item -> {
                    val start = block.depth * LEVEL_EM
                    withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = start.em, restLine = (start + HANG_EM).em))) {
                        appendInlineContent(markerId(block.marker), block.marker)
                        segments(block.segments)
                    }
                }
            }
            previous = block
        }
    }
}

/**
 * [answer] with each citation drawn as a small raised chip in the accent; tapping one calls [onCite] with its number.
 * Headings, lists and code are laid out as [annotatedAnswer] says.
 */
@Composable
internal fun AnswerBody(answer: String, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cite by rememberUpdatedState(onCite)
    val chipText = MaterialTheme.colorScheme.primary
    val chipGround = MaterialTheme.colorScheme.surfaceContainerHigh
    val body = MaterialTheme.typography.bodyLarge
    val text = remember(answer, chipText, chipGround) {
        val chip = SpanStyle(
            color = chipText,
            background = chipGround,
            fontWeight = FontWeight.SemiBold,
            fontSize = 0.75.em,
            baselineShift = BaselineShift.Superscript,
        )
        val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = chipGround)
        annotatedAnswer(answer, chip, code) { cite(it) }
    }
    val markers = remember(answer, body) {
        AnswerText.blocks(answer).filterIsInstance<AnswerText.Block.Item>().map { it.marker }.distinct().associate { marker ->
            markerId(marker) to InlineTextContent(Placeholder(HANG_EM.em, body.lineHeight, PlaceholderVerticalAlign.TextCenter)) {
                Text(marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
    Text(text, modifier, style = body, color = MaterialTheme.colorScheme.onSurface, inlineContent = markers)
}
