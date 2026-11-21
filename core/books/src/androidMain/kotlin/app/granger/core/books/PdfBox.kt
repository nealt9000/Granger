package app.granger.core.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Calendar
import kotlin.math.abs

/** PdfBox-Android reads font metrics from the app's assets; call once, before the first PDF is read. */
object PdfSupport {
    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)
}

/** Opens [file] with PdfBox, buffering in temp files rather than memory (scans run to hundreds of MB). */
internal fun loadPdf(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

internal actual fun platformPdfOpener(): PdfOpener = PdfOpener { path ->
    val doc = try {
        loadPdf(File(path))
    } catch (e: InvalidPasswordException) {
        throw PdfPasswordException(e.message)
    }
    PdfBoxFile(doc)
}

/** A PDF read with PdfBox, as Granger read PDFs before the rules were shared (Plan I5 call 6). */
internal class PdfBoxFile(val doc: PDDocument) : PdfFile {
    override val pageCount: Int get() = doc.numberOfPages
    override val isEncrypted: Boolean get() = doc.isEncrypted

    override fun close() = doc.close()

    override fun info(): PdfInfo {
        val info = doc.documentInformation
        return PdfInfo(
            title = info.title,
            author = info.author,
            subject = info.subject,
            keywords = info.keywords,
            created = info.creationDate?.let(::date),
            language = doc.documentCatalog.language,
        )
    }

    private fun date(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    override fun outline(): List<OutlineEntry> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val out = ArrayList<OutlineEntry>()
        fun walk(node: PDOutlineNode, depth: Int) {
            if (depth > MAX_OUTLINE_DEPTH || out.size >= MAX_OUTLINE) return
            for (item in node.children()) {
                val page = try { item.findDestinationPage(doc) } catch (_: IOException) { null }
                val index = page?.let { doc.pages.indexOf(it) } ?: -1
                val title = item.title?.trim().orEmpty()
                if (index >= 0 && title.isNotEmpty()) out += OutlineEntry(title, index + 1, depth)
                walk(item, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    override fun pageText(page: Int): String {
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            startPage = page
            endPage = page
            lineSeparator = "\n"
            // PdfBox's paragraph guess marks nearly every line; blank lines in the page's own text split instead.
            paragraphStart = ""
            paragraphEnd = ""
        }
        return stripper.getText(doc)
    }

    override fun look(index: Int): PdfOrigin.Companion.PageLook {
        val page = doc.getPage(index)
        val engine = Looker(page)
        try {
            engine.processPage(page)
        } catch (_: IOException) {
            // A page PdfBox can't fully read counts with what it saw.
        }
        return PdfOrigin.Companion.PageLook(engine.visible, engine.hidden, engine.glyphless, engine.pageImage)
    }

    override fun links(max: Int): List<PdfLink> {
        val out = ArrayList<PdfLink>()
        doc.pages.forEachIndexed { index, page ->
            val annotations = try { page.annotations } catch (_: IOException) { return@forEachIndexed }
            for (link in annotations.filterIsInstance<PDAnnotationLink>()) {
                if (out.size >= max) return out
                val target = try { targetOf(link) } catch (_: IOException) { null } ?: continue
                val box = link.rectangle ?: continue
                out += PdfLink(index, box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, target)
            }
        }
        return out
    }

    /** The page [link] goes to: its own destination, else its GoTo action's; null for a web link or a page not here. */
    private fun targetOf(link: PDAnnotationLink): Int? {
        val destination: PDDestination? = link.destination ?: (link.action as? PDActionGoTo)?.destination
        val page = when (destination) {
            is PDPageDestination -> destination
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(destination)
            else -> null
        } ?: return null
        val index = page.page?.let { doc.pages.indexOf(it) } ?: page.pageNumber
        return index.takeIf { it in 0 until doc.numberOfPages }
    }

    override fun pageSize(index: Int): PdfPageSize {
        val page = doc.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        val turned = page.rotation % 180 != 0
        return PdfPageSize(abs(if (turned) box.height else box.width), abs(if (turned) box.width else box.height))
    }

    override fun glyphs(index: Int): List<Glyph> {
        val collector = Collector()
        collector.startPage = index + 1
        collector.endPage = index + 1
        try {
            collector.getText(doc)
        } catch (_: IOException) {
            // What was read before the damage stands.
        }
        return collector.glyphs.map { g ->
            val (bold, italic) = styleOf(g.font)
            Glyph(g.unicode, g.xDirAdj, g.yDirAdj, g.widthDirAdj, g.fontSizeInPt, g.heightDir, bold, italic)
        }
    }

    override fun pictures(index: Int, minArea: Float, scratch: String): List<PdfPicture> {
        val page = doc.getPage(index)
        val engine = PictureFinder(page, index, minArea, File(scratch))
        try {
            engine.processPage(page)
        } catch (_: IOException) {
            // Pictures found before the damage stand.
        }
        return engine.found
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

    private class PictureFinder(page: PDPage, private val index: Int, private val minArea: Float, private val scratch: File) :
        NoPaths(page) {
        val found = mutableListOf<PdfPicture>()
        private val pageHeight = abs((page.cropBox ?: page.mediaBox).height)

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            val w = abs(m.scaleX)
            val h = abs(m.scaleY)
            if (w * h < minArea) return
            val encoded = encode(pdImage) ?: return
            val file = File(scratch, "p${index + 1}-${found.size + 1}").apply { writeBytes(encoded.first) }
            found += PdfPicture(m.translateX, pageHeight - (m.translateY + h), w, h, file.path, encoded.second)
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
    }

    /** Counts a page's glyphs by how they're drawn, and notices a picture of the whole page. */
    private class Looker(page: PDPage) : NoPaths(page) {
        var visible = 0
        var hidden = 0
        var glyphless = 0
        var pageImage = false
        private val pageArea = page.mediaBox.let { abs(it.width * it.height) }.coerceAtLeast(1f)

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            when {
                font.name?.contains("GlyphLess", ignoreCase = true) == true -> glyphless++
                graphicsState.textState.renderingMode.let { it == RenderingMode.NEITHER || it == RenderingMode.NEITHER_CLIP } -> hidden++
                else -> visible++
            }
        }

        override fun drawImage(pdImage: PDImage) {
            val m = graphicsState.currentTransformationMatrix
            if (abs(m.scaleX * m.scaleY) >= pageArea * PAGE_IMAGE) pageImage = true
        }
    }

    /** A graphics engine that ignores paths: only text and pictures matter here. */
    private abstract class NoPaths(page: PDPage) : PDFGraphicsStreamEngine(page) {
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

    companion object {
        private const val MAX_OUTLINE = 2000
        private const val MAX_OUTLINE_DEPTH = 8

        /** The longest side a picture keeps; bigger ones are scaled down. */
        private const val MAX_PICTURE_PX = 1600

        /** An image covering this share of the page is a picture of the whole page. */
        private const val PAGE_IMAGE = 0.6f

        /** Each font's [style], worked out once: asked for every glyph, it is the hottest call in reading a page. */
        private val styles = java.util.Collections.synchronizedMap(java.util.WeakHashMap<PDFont, Pair<Boolean, Boolean>>())

        private val NO_STYLE = false to false
        private val BOLD_NAME = Regex("bold|black|heavy|semibold|demi|-bd|bd$")
        private val ITALIC_NAME = Regex("italic|oblique|-it$|-it[^a-z]|it$|slanted")

        fun styleOf(font: PDFont?): Pair<Boolean, Boolean> = if (font == null) NO_STYLE else styles.getOrPut(font) { style(font) }

        /** Bold and italic from the font's name and descriptor ("Minion-BoldIt", weight 700, the italic flag). */
        fun style(font: PDFont?): Pair<Boolean, Boolean> {
            val name = font?.name.orEmpty().substringAfter('+').lowercase()
            val descriptor = try { font?.fontDescriptor } catch (_: RuntimeException) { null }
            val bold = BOLD_NAME.containsMatchIn(name) ||
                (descriptor?.fontWeight ?: 0f) >= 600f || descriptor?.isForceBold == true
            val italic = ITALIC_NAME.containsMatchIn(name) || descriptor?.isItalic == true
            return bold to italic
        }
    }
}
