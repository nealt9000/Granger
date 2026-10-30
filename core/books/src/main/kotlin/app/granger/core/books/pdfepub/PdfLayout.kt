package app.granger.core.books.pdfepub

import kotlin.math.abs
import kotlin.math.roundToInt

/** What the layout makes of a book's pages, in reading order (Plan IA converter stages 2–5). */
internal sealed interface Item {
    val page: Int
}

/** A heading: its text, its font [size], and whether it [startsPage] (the first thing on its page). */
internal data class Heading(override val page: Int, val spans: List<Span>, val size: Float, val startsPage: Boolean, val y: Float) : Item {
    val text: String get() = spans.joinToString("") { it.text }
}

/** A paragraph of prose, or a caption under a picture. */
internal data class Paragraph(override val page: Int, val spans: List<Span>, val caption: Boolean = false) : Item {
    val text: String get() = spans.joinToString("") { it.text }
}

/** Lines of verse, each with its indent in ems: kept as lines. */
internal data class Verse(override val page: Int, val lines: List<Pair<Int, List<Span>>>) : Item

internal data class Picture(override val page: Int, val picture: PagePicture) : Item

/** A footnote, gathered at the end of its chapter. */
internal data class Note(override val page: Int, val spans: List<Span>) : Item

/** The layout's items and what it saw, for the quality gate. */
internal class Layout(
    val items: List<Item>,
    val bodySize: Float,
    /** Characters drawn, less those dropped as running heads, feet and page numbers. */
    val sourceChars: Int,
    val droppedChars: Int,
    val complexPages: Int,
    val pages: Int,
)

/** Pages into items (Plan IA converter stages 2–5). */
internal object PdfLayout {
    /** Running heads and feet live in this share of the page at its top and its bottom. */
    private const val BAND = 0.09f

    /** A line at least this many times the body size is a heading. */
    private const val HEADING_SIZE = 1.18f

    /** A line at most this many times the body size, at the foot of the page, is a footnote. */
    private const val NOTE_SIZE = 0.88f

    /** A gap between baselines over this many leadings ends a block. */
    private const val BLOCK_GAP = 1.55f

    private val TERMINAL = Regex("[.!?:…]['\"”’)\\]]*$")
    private val PAGE_NUMBER = Regex("^[\\[(]?(\\d{1,4}|[ivxlcdm]{1,7})[\\])]?$", RegexOption.IGNORE_CASE)

    private enum class Kind { BODY, HEADING, NOTE, CAPTION }

    private class Classed(val line: TextLine, val kind: Kind)

    /** An item, whether it [opens] its page or column, and whether its first line is [indented] (for [mergeAcrossBreaks]). */
    private class Placed(val item: Item, val opens: Boolean = false, val indented: Boolean = false)

    /** How a block of body lines reads: clearly verse, clearly prose, or verse-like but not clearly (its neighbours decide). */
    private enum class Shape { VERSE, MAYBE, PROSE }

    /** A block of body lines, decided once every page is read. */
    private class Block(val page: Int, val lines: List<TextLine>, val margin: Margin, val opens: Boolean, val shape: Shape)

    /** A page's output in order: items, and blocks still to decide. */
    private class Slot(val placed: Placed? = null, val block: Block? = null)

    fun build(pages: List<PageText>): Layout {
        val all = pages.flatMap { it.lines }
        val body = bodySize(all)
        val (kept, dropped) = withoutHeadsAndFeet(pages)
        val leading = leading(kept, body)
        val measure = measure(pages, kept, body)
        val slots = mutableListOf<Slot>()
        val notes = mutableListOf<Note>()
        for (page in pages) {
            val lines = kept[page.index].orEmpty()
            val classed = classify(page, lines, body, leading)
            pageItems(page, classed, body, leading, measure, slots, notes)
        }
        val merged = mergeAcrossBreaks(decide(slots))
        // Each page's notes go after that page's last item, so a chapter gathers them.
        val withNotes = (merged + notes).sortedWith(compareBy({ it.page }, { if (it is Note) 1 else 0 }))
        return Layout(
            items = withNotes,
            bodySize = body,
            sourceChars = pages.sumOf { it.rawChars } - dropped,
            droppedChars = dropped,
            complexPages = pages.count { it.complex },
            pages = pages.size,
        )
    }

    /** The size most characters are set in, to the half point. */
    internal fun bodySize(lines: List<TextLine>): Float {
        val weights = HashMap<Float, Int>()
        for (l in lines) weights.merge((l.size * 2f).roundToInt() / 2f, l.text.length, Int::plus)
        return weights.maxByOrNull { it.value }?.key ?: 11f
    }

    /**
     * Drops running heads and feet: a line in the top or bottom band whose words (digits and roman numerals aside)
     * recur on at least three pages and a fifth of them, and a bare page number there. Returns each page's lines left
     * and the characters dropped.
     */
    internal fun withoutHeadsAndFeet(pages: List<PageText>): Pair<Map<Int, List<TextLine>>, Int> {
        fun inBand(page: PageText, line: TextLine) = line.y < page.height * BAND || line.y > page.height * (1f - BAND)
        val counts = HashMap<String, Int>()
        for (page in pages) {
            page.lines.filter { inBand(page, it) }.map { key(it.text) }.filter { it.isNotEmpty() }.distinct()
                .forEach { counts.merge(it, 1, Int::plus) }
        }
        val threshold = maxOf(3, pages.size / 5)
        var dropped = 0
        val kept = pages.associate { page ->
            page.index to page.lines.filter { line ->
                val head = inBand(page, line) && (PAGE_NUMBER.matches(line.text.trim()) ||
                    key(line.text).let { it.isNotEmpty() && (counts[it] ?: 0) >= threshold })
                if (head) dropped += line.text.count { !it.isWhitespace() }
                !head
            }
        }
        return kept to dropped
    }

    /** A running head's words: no digits, no roman numerals, no punctuation, lower case. */
    private fun key(text: String): String = text.lowercase()
        .replace(Regex("\\b[ivxlcdm]+\\b"), " ")
        .replace(Regex("[^\\p{L}]+"), " ")
        .trim()

    /**
     * The width most body lines of one-column pages fill (their 90th percentile, from each page's common left edge):
     * the book's measure. Null without such pages.
     */
    private fun measure(pages: List<PageText>, kept: Map<Int, List<TextLine>>, body: Float): Float? {
        val widths = mutableListOf<Float>()
        for (page in pages) {
            val lines = kept[page.index].orEmpty().filter { abs(it.size - body) < 0.6f }
            if (lines.isEmpty() || lines.any { it.column != 0 }) continue
            val left = margin(lines).left
            lines.filter { abs(it.x - left) < 2f }.forEach { widths += it.right - left }
        }
        if (widths.size < 10) return null
        return widths.sorted()[(widths.size * 9) / 10]
    }

    /** The usual distance between baselines of body text. */
    private fun leading(pages: Map<Int, List<TextLine>>, body: Float): Float {
        val gaps = mutableListOf<Float>()
        for (lines in pages.values) {
            for (i in 1 until lines.size) {
                val a = lines[i - 1]
                val b = lines[i]
                if (a.column != b.column || abs(a.size - body) > 0.6f || abs(b.size - body) > 0.6f) continue
                val gap = b.y - a.y
                if (gap > body * 0.8f && gap < body * 2.5f) gaps += gap
            }
        }
        return gaps.sorted().getOrNull(gaps.size / 2) ?: (body * 1.2f)
    }

    private fun classify(page: PageText, lines: List<TextLine>, body: Float, leading: Float): List<Classed> {
        // The page's foot: the run of small lines at the bottom of each column, below at least three lines of body text.
        // (A page all in small type, a book's endnotes or index, is no page's footnotes.)
        val notes = HashSet<TextLine>()
        for ((_, col) in lines.groupBy { it.column }) {
            val run = col.asReversed().takeWhile { it.size <= body * NOTE_SIZE && it.y > page.height * 0.5f }
            val above = col.size - run.size
            if (run.isNotEmpty() && col.take(above).count { it.size >= body * 0.95f } >= 3) notes += run
        }
        val pictureBottoms = page.pictures.map { it.y + it.h }
        return lines.mapIndexed { i, line ->
            val above = lines.getOrNull(i - 1)
            val gapAbove = if (above == null || above.column != line.column) Float.MAX_VALUE else line.y - above.y
            val kind = when {
                line in notes -> Kind.NOTE
                line.size >= body * HEADING_SIZE && line.text.length <= 140 -> Kind.HEADING
                line.bold && line.text.length <= 90 && gapAbove > leading * BLOCK_GAP && !TERMINAL.containsMatchIn(line.text) &&
                    lines.getOrNull(i + 1)?.let { !it.bold || it.y - line.y > leading * BLOCK_GAP } != false -> Kind.HEADING
                pictureBottoms.any { b -> line.y > b && line.y - b < leading * 2.2f } && (line.italic || line.size < body * 0.95f) -> Kind.CAPTION
                else -> Kind.BODY
            }
            Classed(line, kind)
        }
    }

    /** One page's lines and pictures into items; its notes into [notes]. */
    private fun pageItems(
        page: PageText, classed: List<Classed>, body: Float, leading: Float, measure: Float?, slots: MutableList<Slot>, notes: MutableList<Note>,
    ) {
        fun place(p: Placed) { slots += Slot(placed = p) }
        val pictures = page.pictures.sortedBy { it.y }.toMutableList()
        val bodyLines = classed.filter { it.kind == Kind.BODY }.map { it.line }
        val oneColumn = bodyLines.all { it.column == 0 }
        // On a one-column page the book's usual line width counts as the right edge: a page of verse has no prose to show it.
        val margins = bodyLines.groupBy { it.column }.mapValues { (_, ls) ->
            margin(ls).let { m -> if (oneColumn && measure != null) Margin(m.left, maxOf(m.right, m.left + measure)) else m }
        }
        var block = mutableListOf<TextLine>()
        var startsBoundary = true
        val first = classed.firstOrNull()?.line
        fun flush() {
            if (block.isNotEmpty()) {
                val m = margins[block.first().column] ?: margin(block)
                slots += Slot(block = Block(page.index, block, m, startsBoundary, shape(block, m.left, m.right)))
            }
            block = mutableListOf()
            startsBoundary = false
        }
        var i = 0
        while (i < classed.size) {
            val c = classed[i]
            val line = c.line
            // Pictures above this line come first.
            while (pictures.isNotEmpty() && pictures.first().y + pictures.first().h <= line.y) {
                flush()
                place(Placed(Picture(page.index, pictures.removeAt(0))))
            }
            when (c.kind) {
                Kind.NOTE -> {
                    val run = mutableListOf(line)
                    while (i + 1 < classed.size && classed[i + 1].kind == Kind.NOTE) run += classed[++i].line
                    notes += noteItems(page.index, run, body)
                }
                Kind.HEADING -> {
                    flush()
                    val run = mutableListOf(line)
                    // Lines of one heading: the same size, close below (a chapter's number on a line of its own
                    // above its title, "1" over "“WE HAVE SOME PLANES”", is further).
                    while (i + 1 < classed.size && classed[i + 1].kind == Kind.HEADING &&
                        abs(classed[i + 1].line.size - line.size) < 0.6f &&
                        classed[i + 1].line.y - run.last().y < leading * (if (run.size == 1 && isNumber(run[0].text)) 4.5f else 2.2f)
                    ) run += classed[++i].line
                    place(Placed(Heading(page.index, joinLines(run), line.size, startsPage = line === first, y = line.y)))
                }
                Kind.CAPTION -> {
                    flush()
                    place(Placed(Paragraph(page.index, line.spans, caption = true)))
                }
                Kind.BODY -> {
                    val prev = block.lastOrNull()
                    if (prev != null && (prev.column != line.column || line.y - prev.y > leading * BLOCK_GAP || line.y < prev.y ||
                            abs(line.size - prev.size) > body * 0.12f)
                    ) {
                        val newColumn = prev.column != line.column
                        flush()
                        if (newColumn) startsBoundary = true
                    }
                    block += line
                }
            }
            i++
        }
        flush()
        pictures.forEach { place(Placed(Picture(page.index, it))) }
    }

    private fun isNumber(text: String) = text.trim().let { it.isNotEmpty() && it.length <= 6 && PAGE_NUMBER.matches(it) }

    /** Where a column's lines start and end: the commonest start, and the right edge most lines reach. */
    private class Margin(val left: Float, val right: Float) {
        val width: Float get() = (right - left).coerceAtLeast(1f)
    }

    private fun margin(lines: List<TextLine>): Margin {
        if (lines.isEmpty()) return Margin(0f, 1f)
        val left = lines.groupingBy { it.x.roundToInt() }.eachCount().maxByOrNull { it.value }!!.key.toFloat()
        // The right edge most lines reach: the odd overlong line (a long web address) doesn't move it.
        val rights = lines.map { it.right }.sorted()
        return Margin(left, rights[minOf(rights.size - 1, (rights.size * 19) / 20)])
    }

    /**
     * Each block's verse or prose: a verse-like block that isn't clearly verse ([Shape.MAYBE]: lines too full to tell) is
     * verse when the nearest clear block before or after it, within two pages, is.
     */
    private fun decide(slots: List<Slot>): List<Placed> {
        fun near(i: Int, page: Int, step: Int): Shape? {
            var k = i + step
            while (k in slots.indices) {
                val b = slots[k].block
                if (b != null && kotlin.math.abs(b.page - page) > 1) return null
                if (b != null && b.shape != Shape.MAYBE) return b.shape
                k += step
            }
            return null
        }
        return slots.withIndex().flatMap { (i, slot) ->
            val b = slot.block ?: return@flatMap listOf(slot.placed!!)
            val verse = when (b.shape) {
                Shape.VERSE -> true
                Shape.PROSE -> false
                Shape.MAYBE -> near(i, b.page, -1) == Shape.VERSE || near(i, b.page, 1) == Shape.VERSE
            }
            blockItems(b, verse)
        }
    }

    /** A block's lines as verse (kept line by line) or prose (paragraphs), marked when it opens a page or column. */
    private fun blockItems(b: Block, verse: Boolean): List<Placed> {
        val lines = b.lines
        val margin = b.margin
        if (verse) {
            val left = lines.minOf { it.x }
            val v = Verse(b.page, lines.map { ((it.x - left) / it.size).roundToInt().coerceIn(0, 12) to it.spans })
            return listOf(Placed(v, opens = b.opens))
        }
        val paragraphs = mutableListOf<MutableList<TextLine>>()
        for ((i, line) in lines.withIndex()) {
            val prev = lines.getOrNull(i - 1)
            val indented = line.x - margin.left > line.size * 0.6f
            val prevShort = prev != null && prev.right < margin.right - line.size * 1.5f && TERMINAL.containsMatchIn(prev.text.trimEnd())
            if (prev == null || indented || prevShort) paragraphs += mutableListOf(line) else paragraphs.last() += line
        }
        return paragraphs.mapIndexed { i, p ->
            Placed(Paragraph(b.page, joinLines(p)), opens = b.opens && i == 0, indented = p.first().x - margin.left > p.first().size * 0.6f)
        }
    }

    /**
     * Verse: most lines break early, where the next line's first word would have fitted on them (prose, justified or
     * ragged, breaks only when the next word won't fit, and at paragraph ends); few lines reach the right edge [right]
     * or end in a hyphen; and most start at one place (a novel's dialogue is short lines too, but each starts indented).
     * Without the early breaks but with the rest it is [Shape.MAYBE]: lines of verse can fill the measure.
     */
    private fun shape(lines: List<TextLine>, left: Float, right: Float): Shape {
        val edge = maxOf(right, lines.maxOf { it.right })
        val width = (edge - left).coerceAtLeast(1f)
        // Lines that end a sentence say nothing: a paragraph's last line stops short in prose too.
        val open = (0 until lines.size - 1).filter { !TERMINAL.containsMatchIn(lines[it].text.trimEnd()) }
        val early = open.count { i ->
            val line = lines[i]
            val advance = (line.right - line.x) / line.text.length.coerceAtLeast(1)
            val nextWord = lines[i + 1].text.trimStart().takeWhile { !it.isWhitespace() }.length * advance
            edge - line.right > nextWord + advance * 2f
        }
        val atMargin = lines.count { it.right >= edge - width * 0.015f }
        val hyphens = lines.count { it.text.trimEnd().endsWith("-") }
        val commonStart = lines.groupingBy { (it.x / 2f).roundToInt() }.eachCount().maxOf { it.value }
        val verseLike = lines.size >= 2 && atMargin * 10 < lines.size * 4 && hyphens * 10 < lines.size && commonStart * 10 >= lines.size * 6
        return when {
            !verseLike -> Shape.PROSE
            lines.size >= 5 && open.size >= 3 && early * 10 >= open.size * 7 && atMargin * 10 < lines.size * 3 -> Shape.VERSE
            else -> Shape.MAYBE
        }
    }

    /** [shape]'s verdict on its own, for tests: verse only when clearly so. */
    internal fun isVerseShape(lines: List<TextLine>, left: Float, right: Float): Boolean = shape(lines, left, right) == Shape.VERSE

    private fun noteItems(page: Int, lines: List<TextLine>, body: Float): List<Note> {
        val out = mutableListOf<MutableList<TextLine>>()
        val left = lines.minOf { it.x }
        for (line in lines) {
            val starts = out.isEmpty() || line.x - left > line.size * 0.6f || Regex("^[\\d*†‡§]+[.)]?\\s").containsMatchIn(line.text)
            if (starts) out += mutableListOf(line) else out.last() += line
        }
        return out.map { Note(page, joinLines(it)) }
    }

    /** Lines into one run of spans: a hyphen at a line's end joins a word broken across lines, a space joins the rest. */
    internal fun joinLines(lines: List<TextLine>): List<Span> {
        val out = mutableListOf<Span>()
        for (line in lines) {
            if (out.isEmpty()) {
                out += line.spans
                continue
            }
            out[out.size - 1] = joinSpans(out.last(), line.spans.first()).also { (a, b) -> if (b != null) out += b }.first
            out += line.spans.drop(1)
        }
        return merge(out)
    }

    /** The end of one line's last span and the start of the next line's first, joined; the second is null when merged in. */
    private fun joinSpans(a: Span, b: Span): Pair<Span, Span?> {
        val end = a.text.trimEnd()
        val next = b.text.trimStart()
        val brokenWord = end.endsWith("-") || end.endsWith("­") || end.endsWith("¬")
        val head = when {
            brokenWord && next.firstOrNull()?.isLowerCase() == true && end.length > 1 && end[end.length - 2].isLetter() -> end.dropLast(1)
            brokenWord && end.endsWith("-") -> end
            brokenWord -> end.dropLast(1)
            end.endsWith("—") || end.endsWith("/") -> end
            else -> "$end "
        }
        return if (a.italic == b.italic && a.bold == b.bold) a.copy(text = head + next) to null
        else a.copy(text = head) to b.copy(text = next)
    }

    private fun merge(spans: List<Span>): List<Span> = spans.fold(mutableListOf()) { acc, sp ->
        val last = acc.lastOrNull()
        if (last != null && last.italic == sp.italic && last.bold == sp.bold) acc[acc.size - 1] = last.copy(text = last.text + sp.text)
        else if (sp.text.isNotEmpty()) acc += sp
        acc
    }

    /**
     * Joins a paragraph broken by a page or column with its rest: an unfinished paragraph followed, across nothing but
     * pictures, by one that opens the page unindented. Verse that runs over a page joins the verse before it.
     */
    private fun mergeAcrossBreaks(items: List<Placed>): List<Item> {
        val out = mutableListOf<Item>()
        for (placed in items) {
            val item = placed.item
            if (placed.opens) {
                val lastText = out.indexOfLast { it !is Picture }
                val before = out.getOrNull(lastText)
                if (item is Paragraph && before is Paragraph && !before.caption && !placed.indented && !TERMINAL.containsMatchIn(before.text.trimEnd())) {
                    val joined = joinSpans(before.spans.last(), item.spans.first())
                    out[lastText] = before.copy(spans = merge(before.spans.dropLast(1) + listOfNotNull(joined.first, joined.second) + item.spans.drop(1)))
                    continue
                }
                if (item is Verse && before is Verse) {
                    out[lastText] = before.copy(lines = before.lines + item.lines)
                    continue
                }
            }
            out += item
        }
        return out
    }
}
