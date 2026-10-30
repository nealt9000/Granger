package app.granger.core.books.pdfepub

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** A run of text in one style within a line or a paragraph. */
internal data class Span(val text: String, val italic: Boolean = false, val bold: Boolean = false)

/**
 * One line of a page as a reader sees it: [x] to [right] across, its baseline [y] from the page's top, its biggest
 * font [size], and its [spans]. [bold] and [italic] say whether most of its characters are.
 */
internal data class TextLine(
    val page: Int,
    val x: Float,
    val right: Float,
    val y: Float,
    val size: Float,
    val spans: List<Span>,
    val bold: Boolean,
    val italic: Boolean,
    /** 0 on a one-column page and for the left column, 1 for the right, -1 for a line across both. */
    val column: Int = 0,
) {
    val text: String get() = spans.joinToString("") { it.text }
}

/** A picture drawn on a page, with its place (top-down) and its [file], encoded for the EPUB, in the scratch folder. */
internal class PagePicture(
    val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val file: File, val mediaType: String,
)

/** A page's lines in reading order, and its pictures; [complex] when its layout has three or more columns. */
internal class PageText(
    val index: Int,
    val width: Float,
    val height: Float,
    val lines: List<TextLine>,
    val pictures: List<PagePicture>,
    val complex: Boolean,
    /** Characters drawn on the page, spaces aside: the quality gate's denominator. */
    val rawChars: Int,
    /** 0 or 1 for the left or right half of a spread of two printed pages; null for a page as it is. */
    val spreadHalf: Int? = null,
    /** The PDF page it is (or half of), from 0: an outline's pages count these. */
    val pdfPage: Int = index,
    /** Where the gutter was found on the PDF page, if there was one. */
    val gutter: Float? = null,
) {
    /** The same page numbered [to] in reading order: its lines and pictures follow. */
    fun renumbered(to: Int): PageText = PageText(
        to, width, height, lines.map { it.copy(page = to) },
        pictures.map { PagePicture(to, it.x, it.y, it.w, it.h, it.file, it.mediaType) }, complex, rawChars, spreadHalf, pdfPage, gutter,
    )
}

/** Reads a PDF page by page into lines and pictures (Plan IA converter stage 1). */
internal object PdfPages {
    /** Pictures smaller than this share of the page (icons, rules, ornaments) are left out. */
    private const val MIN_PICTURE = 1f / 16f

    /** The longest side a picture keeps; bigger ones are scaled down. */
    private const val MAX_PICTURE_PX = 1600

    /**
     * Page [index]: its lines, and its pictures written into [scratch] (a book's pictures don't all fit in memory). Two
     * pages when it is a spread of two printed pages.
     */
    fun read(doc: PDDocument, index: Int, scratch: File, spreadGutter: Float? = null): List<PageText> {
        val page = doc.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        // A page stored portrait and turned a quarter for display is read turned (the glyphs' positions already are).
        val turned = page.rotation % 180 != 0
        val width = abs(if (turned) box.height else box.width)
        val height = abs(if (turned) box.width else box.height)
        val glyphs = glyphs(doc, index)
        // A book of spreads splits every landscape page at its gutter, a nearly empty half (a chapter's end) too.
        val forced = spreadGutter?.takeIf { width > height * 1.1f }
        val laid = Lines.of(index, glyphs, width, forced)
        val pictures = pictures(page, index, width * height, scratch)
        val chars = glyphs.count { !it.unicode.isNullOrBlank() }
        val gutter = laid.gutter
        // A landscape page with a gutter down its middle is two printed pages side by side (a spread, or a booklet's
        // imposition): each half is a page of its own.
        if (gutter != null && width > height * 1.1f && gutter > width * 0.4f && gutter < width * 0.6f) {
            fun half(col: Int, pics: List<PagePicture>) = PageText(
                index, width / 2f, height, laid.lines.filter { it.column == col }.map { it.copy(column = 0) }, pics, laid.complex,
                chars / 2, spreadHalf = col, gutter = gutter,
            )
            val (left, right) = pictures.partition { it.x + it.w / 2f < gutter }
            return listOf(half(0, left), half(1, right))
        }
        return listOf(PageText(index, width, height, laid.lines, pictures, laid.complex, chars, gutter = gutter))
    }

    /**
     * Every page, in reading order: when most landscape pages are spreads, those that weren't found to be (a half nearly
     * empty) are read again split at the spreads' usual gutter.
     */
    fun readAll(doc: PDDocument, scratch: File): List<PageText> {
        val pages = (0 until doc.numberOfPages).map { read(doc, it, scratch) }
        val spreads = pages.filter { it.size == 2 }
        val wholeLandscape = pages.withIndex().filter { (_, p) -> p.size == 1 && p[0].width > p[0].height * 1.1f }
        val gutter = spreads.mapNotNull { it[0].gutter }.sorted().let { it.getOrNull(it.size / 2) }
        val all = if (gutter != null && spreads.size >= 2 && spreads.size * 4 >= (spreads.size + wholeLandscape.size) * 3) {
            pages.mapIndexed { i, p -> if (p.size == 1 && p[0].width > p[0].height * 1.1f) read(doc, i, scratch, gutter) else p }
        } else {
            pages
        }
        return inReadingOrder(all.flatten())
    }

    private fun glyphs(doc: PDDocument, index: Int): List<TextPosition> {
        val collector = Collector()
        collector.startPage = index + 1
        collector.endPage = index + 1
        try {
            collector.getText(doc)
        } catch (_: IOException) {
            // What was read before the damage stands.
        }
        return collector.glyphs
    }

    /** Every glyph as drawn, without the stripper's own line building. */
    private class Collector : PDFTextStripper() {
        val glyphs = mutableListOf<TextPosition>()

        init {
            sortByPosition = true
        }

        override fun processTextPosition(text: TextPosition) {
            // Some PDFs fake bold by drawing a glyph twice, a hair apart: keep one.
            val last = glyphs.lastOrNull()
            if (last != null && last.unicode == text.unicode && abs(last.xDirAdj - text.xDirAdj) < text.fontSizeInPt * 0.1f &&
                abs(last.yDirAdj - text.yDirAdj) < 0.5f
            ) return
            glyphs += text
        }
    }

    private fun pictures(page: PDPage, index: Int, pageArea: Float, scratch: File): List<PagePicture> {
        val engine = PictureFinder(page, index, pageArea * MIN_PICTURE, scratch)
        try {
            engine.processPage(page)
        } catch (_: IOException) {
            // Pictures found before the damage stand.
        }
        return engine.found
    }

    private class PictureFinder(page: PDPage, private val index: Int, private val minArea: Float, private val scratch: File) :
        PDFGraphicsStreamEngine(page) {
        val found = mutableListOf<PagePicture>()
        private val pageHeight = abs((page.cropBox ?: page.mediaBox).height)

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val w = abs(m.scaleX)
            val h = abs(m.scaleY)
            if (w * h < minArea) return
            val encoded = encode(pdImage) ?: return
            val file = File(scratch, "p${index + 1}-${found.size + 1}").apply { writeBytes(encoded.first) }
            found += PagePicture(index, m.translateX, pageHeight - (m.translateY + h), w, h, file, encoded.second)
        }

        /** A JPEG as it is; anything else decoded and stored as PNG (with transparency) or JPEG. */
        private fun encode(image: PDImage): Pair<ByteArray, String>? = try {
            val x = image as? PDImageXObject
            if (x != null && x.suffix == "jpg" && x.width <= MAX_PICTURE_PX && x.height <= MAX_PICTURE_PX) {
                x.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { it.readBytes() } to "image/jpeg"
            } else {
                image.image?.let { b ->
                    val scale = minOf(1f, MAX_PICTURE_PX.toFloat() / maxOf(b.width, b.height))
                    val sized = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true) else b
                    val png = sized.hasAlpha()
                    val bytes = ByteArrayOutputStream().also {
                        sized.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
                    }.toByteArray()
                    if (sized !== b) sized.recycle()
                    b.recycle()
                    bytes to if (png) "image/png" else "image/jpeg"
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }

    /**
     * [pages] in reading order, numbered from 0. A booklet's imposition puts printed pages 14 and 3 side by side: when
     * most pages are spread halves and nearly all of those carry a page number at their head or foot (each once), the
     * halves go in page-number order, any without one (a cover, a title) first in their own order.
     */
    fun inReadingOrder(pages: List<PageText>): List<PageText> {
        val halves = pages.count { it.spreadHalf != null }
        var ordered = pages
        if (halves >= 4 && halves * 5 >= pages.size * 4) {
            val folios = withPartnersFolios(pages, pages.map(::folio))
            val known = folios.filterNotNull()
            if (known.size * 5 >= halves * 4 && known.toSet().size == known.size) {
                ordered = pages.indices.sortedWith(compareBy({ folios[it] != null }, { folios[it] ?: it })).map { pages[it] }
            }
        }
        return ordered.mapIndexed { i, p -> if (p.index == i) p else p.renumbered(i) }
    }

    /**
     * Fills in a spread half's missing page number from the half beside it (a chapter's first page often has none). In a
     * booklet's imposition the two halves' numbers add up to the same total throughout (14 + 3, 16 + 1); on plain
     * spreads the right is the left plus one. Whichever most complete spreads show is used; with neither, nothing is.
     */
    private fun withPartnersFolios(pages: List<PageText>, folios: List<Int?>): List<Int?> {
        val pairs = pages.indices.filter { pages[it].spreadHalf == 0 && pages.getOrNull(it + 1)?.spreadHalf == 1 }
        val complete = pairs.mapNotNull { i -> folios[i]?.let { l -> folios[i + 1]?.let { r -> l to r } } }
        if (complete.size < 2) return folios
        val sum = complete.groupingBy { it.first + it.second }.eachCount().maxBy { it.value }
        val sequential = complete.count { it.second == it.first + 1 }
        val out = folios.toMutableList()
        for (i in pairs) {
            val l = folios[i]
            val r = folios[i + 1]
            when {
                sequential * 2 > complete.size -> {
                    if (l == null && r != null) out[i] = r - 1
                    if (r == null && l != null) out[i + 1] = l + 1
                }
                sum.value * 2 > complete.size -> {
                    if (l == null && r != null) out[i] = sum.key - r
                    if (r == null && l != null) out[i + 1] = sum.key - l
                }
            }
        }
        return out
    }

    /** The page number printed at the page's head or foot, if any. */
    private fun folio(page: PageText): Int? = page.lines
        .filter { it.y < page.height * 0.09f || it.y > page.height * 0.91f }
        .firstNotNullOfOrNull { it.text.trim().takeIf { t -> t.length <= 4 && t.all(Char::isDigit) }?.toIntOrNull() }

    /** Bold and italic from the font's name and descriptor ("Minion-BoldIt", weight 700, the italic flag). */
    fun style(font: PDFont?): Pair<Boolean, Boolean> {
        val name = font?.name.orEmpty().substringAfter('+').lowercase()
        val descriptor = try { font?.fontDescriptor } catch (_: RuntimeException) { null }
        val bold = Regex("bold|black|heavy|semibold|demi|-bd|bd$").containsMatchIn(name) ||
            (descriptor?.fontWeight ?: 0f) >= 600f || descriptor?.isForceBold == true
        val italic = Regex("italic|oblique|-it$|-it[^a-z]|it$|slanted").containsMatchIn(name) || descriptor?.isItalic == true
        return bold to italic
    }
}

/** Glyphs into lines (Plan IA converter stage 1): baselines, spaces, margin line numbers, columns. */
internal object Lines {
    /** A gap wider than this many font sizes splits a line into segments (columns, a margin number). */
    private const val SEGMENT_GAP = 2.0f

    private class Segment(val glyphs: List<TextPosition>) {
        val x: Float = glyphs.first().xDirAdj
        val right: Float = glyphs.maxOf { it.xDirAdj + it.widthDirAdj }
        val text: String get() = glyphs.joinToString("") { it.unicode.orEmpty() }
    }

    /** A page's lines in reading order, whether they were too complex to trust, and the gutter between two columns. */
    class Laid(val lines: List<TextLine>, val complex: Boolean, val gutter: Float?)

    /**
     * The page's lines in reading order: by baseline, and on a page of two columns the left column before the right
     * (lines across both, a title, keep their place). Rows with three or more segments on a fifth of the page mark it
     * complex (tables, three columns).
     */
    fun of(page: Int, glyphs: List<TextPosition>, pageWidth: Float, forcedGutter: Float? = null): Laid {
        // Spaces are left out and put back from the gaps: some PDFs fill a gap (before a margin number) with them.
        val rows = rows(glyphs.filter { !it.unicode.isNullOrBlank() })
        val gutter = forcedGutter ?: gutter(rows, pageWidth)
        var multi = 0
        fun segmented(glyphs: List<TextPosition>): List<TextPosition> {
            val segs = segments(glyphs)
            if (segs.size >= 3) multi++
            // Margin line numbers (verse's every tenth line, a legal text's) are dropped: a lone number far from the text.
            return (if (segs.size > 1) segs.filterNot { isMarginNumber(it) } else segs).flatMap(Segment::glyphs)
        }
        val lines = if (gutter == null) {
            rows.map(::segmented).filter { it.isNotEmpty() }.map { line(page, it) }
        } else {
            twoColumns(page, rows, gutter, ::segmented)
        }
        val complex = rows.isNotEmpty() && multi * 5 > rows.size
        return Laid(lines, complex, gutter)
    }

    /** Glyphs grouped by baseline: a glyph joins a row when its baseline is within half its size of the row's. */
    private fun rows(glyphs: List<TextPosition>): List<List<TextPosition>> {
        val sorted = glyphs.sortedWith(compareBy({ it.yDirAdj }, { it.xDirAdj }))
        val rows = mutableListOf<MutableList<TextPosition>>()
        var baseline = Float.NaN
        for (g in sorted) {
            val size = g.fontSizeInPt.takeIf { it > 0f } ?: g.heightDir
            if (rows.isEmpty() || abs(g.yDirAdj - baseline) > size * 0.5f) {
                rows += mutableListOf(g)
                baseline = g.yDirAdj
            } else {
                rows.last() += g
            }
        }
        return rows.map { row -> row.sortedBy { it.xDirAdj } }
    }

    private fun segments(row: List<TextPosition>): List<Segment> {
        val out = mutableListOf<MutableList<TextPosition>>()
        var right = Float.NEGATIVE_INFINITY
        for (g in row) {
            val size = g.fontSizeInPt.takeIf { it > 0f } ?: 10f
            if (out.isEmpty() || g.xDirAdj - right > size * SEGMENT_GAP) out += mutableListOf(g) else out.last() += g
            right = maxOf(right, g.xDirAdj + g.widthDirAdj)
        }
        return out.map { Segment(it) }
    }

    private fun isMarginNumber(s: Segment): Boolean = s.text.trim().let { it.isNotEmpty() && it.length <= 4 && it.all(Char::isDigit) }

    /**
     * The gutter between two columns: the widest vertical strip in the page's middle half that hardly any row's glyphs
     * cross (at most one in thirty), at least half a font size wide, with text on each side of it in at least a quarter
     * of the rows. Null for a page of one column.
     */
    private fun gutter(rows: List<List<TextPosition>>, pageWidth: Float): Float? {
        if (rows.size < 8 || pageWidth <= 0f) return null
        val width = pageWidth.toInt().coerceAtLeast(1)
        val cover = IntArray(width + 1)
        for (row in rows) {
            val seen = BooleanArray(width + 1)
            for (g in row) {
                val a = g.xDirAdj.toInt().coerceIn(0, width)
                val b = (g.xDirAdj + g.widthDirAdj).toInt().coerceIn(0, width)
                for (x in a..b) seen[x] = true
            }
            for (x in 0..width) if (seen[x]) cover[x]++
        }
        val allowed = rows.size / 30
        val size = rows.flatten().map { it.fontSizeInPt }.filter { it > 0f }.sorted().let { it.getOrNull(it.size / 2) ?: 10f }
        var best: IntRange? = null
        var start = -1
        for (x in (width / 4)..(width * 3 / 4) + 1) {
            val clear = x <= width * 3 / 4 && cover[x] <= allowed
            if (clear && start < 0) start = x
            if (!clear && start >= 0) {
                if (best == null || x - start > best.last - best.first + 1) best = start until x
                start = -1
            }
        }
        val strip = best?.takeIf { it.last - it.first + 1 >= size * 0.5f } ?: return null
        val middle = (strip.first + strip.last) / 2f
        // Each side holds a good share of the rows: columns and facing pages don't share baselines.
        val left = rows.count { row -> row.any { it.xDirAdj + it.widthDirAdj <= strip.first } }
        val right = rows.count { row -> row.any { it.xDirAdj >= strip.last } }
        return middle.takeIf { left * 4 >= rows.size && right * 4 >= rows.size }
    }

    /** Left column, then right, within each run of rows between ones across both (a title over the columns). */
    private fun twoColumns(page: Int, rows: List<List<TextPosition>>, gutter: Float, clean: (List<TextPosition>) -> List<TextPosition>): List<TextLine> {
        val out = mutableListOf<TextLine>()
        val left = mutableListOf<TextLine>()
        val right = mutableListOf<TextLine>()
        fun flush() { out += left; out += right; left.clear(); right.clear() }
        for (row in rows) {
            val across = row.any { it.xDirAdj < gutter && it.xDirAdj + it.widthDirAdj > gutter }
            if (across) {
                flush()
                clean(row).takeIf { it.isNotEmpty() }?.let { out += line(page, it).copy(column = -1) }
                continue
            }
            clean(row.filter { it.xDirAdj < gutter }).takeIf { it.isNotEmpty() }?.let { left += line(page, it) }
            clean(row.filter { it.xDirAdj >= gutter }).takeIf { it.isNotEmpty() }?.let { right += line(page, it).copy(column = 1) }
        }
        flush()
        return out
    }

    /** One line from its glyphs: spaces where the gaps are, spans by style. */
    private fun line(page: Int, glyphs: List<TextPosition>): TextLine {
        val spans = mutableListOf<Span>()
        val text = StringBuilder()
        var style: Pair<Boolean, Boolean>? = null
        var right = Float.NaN
        var boldChars = 0
        var italicChars = 0
        var chars = 0
        fun flush() {
            if (text.isNotEmpty()) spans += Span(text.toString(), italic = style!!.second, bold = style!!.first)
            text.setLength(0)
        }
        for (g in glyphs) {
            val s = PdfPages.style(g.font)
            val size = g.fontSizeInPt.takeIf { it > 0f } ?: 10f
            val gap = if (right.isNaN()) 0f else g.xDirAdj - right
            // Ligatures as their letters ("ﬁ" → "fi"), so search and the voice find the words.
            val u = g.unicode.orEmpty().let { LIGATURES[it] ?: it }
            if (style != null && s != style) flush()
            style = s
            if (gap > size * 0.12f && text.lastOrNull() != ' ' && spans.lastOrNull()?.text?.endsWith(" ") != true && !u.startsWith(" ")) {
                text.append(' ')
            }
            text.append(u)
            right = g.xDirAdj + g.widthDirAdj
            if (u.isNotBlank()) {
                chars += u.length
                if (s.first) boldChars += u.length
                if (s.second) italicChars += u.length
            }
        }
        flush()
        val merged = spans.fold(mutableListOf<Span>()) { acc, sp ->
            val last = acc.lastOrNull()
            if (last != null && last.italic == sp.italic && last.bold == sp.bold) acc[acc.size - 1] = last.copy(text = last.text + sp.text) else acc += sp
            acc
        }
        return TextLine(
            page = page,
            x = glyphs.first().xDirAdj,
            right = glyphs.maxOf { it.xDirAdj + it.widthDirAdj },
            y = glyphs.maxOf { it.yDirAdj },
            size = glyphs.maxOf { it.fontSizeInPt.takeIf { s -> s > 0f } ?: 10f },
            spans = trimSpans(merged),
            bold = chars > 0 && boldChars * 2 > chars,
            italic = chars > 0 && italicChars * 2 > chars,
        )
    }

    private val LIGATURES = mapOf("ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "st", "ﬆ" to "st")

    private fun trimSpans(spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return spans
        val out = spans.toMutableList()
        out[0] = out[0].copy(text = out[0].text.trimStart())
        out[out.size - 1] = out[out.size - 1].copy(text = out[out.size - 1].text.trimEnd())
        return out.filter { it.text.isNotEmpty() }
    }
}
