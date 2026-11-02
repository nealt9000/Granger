package app.granger.core.books

import app.granger.core.text.LanguageGuess
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities

/** Converts plain text or Markdown into an EPUB 3 laid out like the pack builder's (mimetype, container, OPF, nav, cNNN.xhtml). */
object TextToEpub {
    private class Chapter(val title: String, val body: String)

    private val HEADING = Regex("(?m)^\\s*(CHAPTER|Chapter|BOOK|Book|PART|Part)\\s+([0-9]+|[IVXLCDM]+)\\b.*$")
    private val BLANK_LINES = Regex("(?:\\r?\\n[ \\t]*){2,}")

    /**
     * [language] is the book's declared language (archive.org's metadata); blank or unknown, the text's own is guessed,
     * Spanish or English (Plan L3, [LanguageGuess]).
     */
    fun convert(text: String, title: String, markdown: Boolean, out: OutputStream, maxChapterChars: Int = 100_000, language: String? = null) {
        val clean = stripInvalidXml(text)
        val chapters = (if (markdown) markdownChapters(clean) else txtChapters(clean, maxChapterChars))
            .ifEmpty { listOf(Chapter("Part 1", "")) }
        write(chapters, stripInvalidXml(title), clean, out, LanguageGuess.tag(language, clean))
    }

    /**
     * The title a text names for itself, or null to keep the file name: Markdown's first `# heading`; Project
     * Gutenberg's "Title:" line or its "The Project Gutenberg eBook of X" opening; else a short first line that stands
     * alone (a blank line after it, no closing full stop, not "Chapter 1").
     */
    fun titleOf(text: String, markdown: Boolean): String? {
        val lines = text.lineSequence().take(TITLE_SCAN_LINES).toList()
        fun ok(t: String?) = t?.trim()?.trim('*', '_', '#', ' ')?.takeIf { it.length in 2..MAX_TITLE && it.any(Char::isLetter) }
        if (markdown) return ok(lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# "))
        lines.firstNotNullOfOrNull { GUTENBERG_TITLE.matchEntire(it.trim())?.groupValues?.get(1) }?.let { t -> ok(t)?.let { return it } }
        lines.firstNotNullOfOrNull { GUTENBERG_OPENING.find(it)?.groupValues?.get(1) }?.let { t -> ok(t.substringBefore(", by "))?.let { return it } }
        val first = lines.indexOfFirst { it.isNotBlank() }.takeIf { it >= 0 } ?: return null
        val line = lines[first].trim()
        val standsAlone = lines.getOrNull(first + 1)?.isBlank() ?: true
        if (!standsAlone || line.endsWith('.') || line.endsWith(',') || HEADING.matches(line)) return null
        return ok(line)
    }

    private const val TITLE_SCAN_LINES = 60
    private const val MAX_TITLE = 80
    private val GUTENBERG_TITLE = Regex("Title:\\s+(.+)")
    private val GUTENBERG_OPENING = Regex("The Project Gutenberg e[Bb]ook,? of (.+)")

    // ---- TXT ----

    private fun txtChapters(text: String, max: Int): List<Chapter> {
        val headings = HEADING.findAll(text).toList()
        if (headings.size >= 2) {
            val bounds = headings.map { it.range.first } + text.length
            val result = ArrayList<Chapter>()
            val preamble = text.substring(0, bounds[0])
            if (preamble.isNotBlank()) result.add(Chapter("Part ${result.size + 1}", paragraphs(preamble)))
            headings.forEachIndexed { i, h ->
                val heading = h.value.trim()
                val rest = text.substring(h.range.last + 1, bounds[i + 1])
                result.add(Chapter(heading, "<h2>${esc(heading)}</h2>" + paragraphs(rest)))
            }
            return result
        }
        val chapters = ArrayList<Chapter>()
        val body = StringBuilder()
        var size = 0
        fun flush() {
            if (body.isNotEmpty()) chapters.add(Chapter("Part ${chapters.size + 1}", body.toString()))
            body.setLength(0)
            size = 0
        }
        for (para in paragraphList(text)) {
            for (piece in cutAtSpaces(para, max)) {
                if (size > 0 && size + piece.length > max) flush()
                body.append("<p>").append(esc(piece)).append("</p>")
                size += piece.length
            }
        }
        flush()
        return chapters
    }

    /**
     * Paragraphs split at blank lines. A long block whose lines are themselves long is a file with one paragraph per
     * line (no blank lines between): each line is a paragraph. Hard-wrapped text (lines of about 70 characters) and
     * verse keep their lines together as before.
     */
    private fun paragraphList(text: String): List<String> =
        text.split(BLANK_LINES).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (block.length > LINE_PARAGRAPH_BLOCK && lines.size > 1 && lines.sumOf { it.length } / lines.size > LINE_PARAGRAPH_AVG) {
                lines
            } else {
                listOf(block)
            }
        }

    private const val LINE_PARAGRAPH_BLOCK = 1_500
    private const val LINE_PARAGRAPH_AVG = 120

    private fun paragraphs(text: String): String {
        val sb = StringBuilder()
        for (p in paragraphList(text)) sb.append("<p>").append(esc(p)).append("</p>")
        return sb.toString()
    }

    // ---- Markdown ----

    private fun markdownChapters(text: String): List<Chapter> {
        val doc = Parser.builder().build().parse(text)
        doc.accept(object : AbstractVisitor() {
            override fun visit(image: Image) = image.unlink()
        })
        val counts = HashMap<Int, Int>()
        var n: Node? = doc.firstChild
        while (n != null) {
            if (n is Heading) counts.merge(n.level, 1, Int::plus)
            n = n.next
        }
        val level = counts.filterValues { it >= 2 }.keys.minOrNull()

        val html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(doc)
        val nodes = Jsoup.parseBodyFragment(html).body().children()
        val result = ArrayList<Chapter>()
        var title: String? = null
        val current = ArrayList<org.jsoup.nodes.Element>()
        fun flush() {
            if (current.isEmpty()) return
            val t = title ?: current.firstOrNull { it.tagName().matches(Regex("h[1-6]")) }?.text()?.takeIf { it.isNotBlank() }
            result.add(Chapter(t ?: "Part ${result.size + 1}", xhtml(current)))
            current.clear()
            title = null
        }
        for (el in nodes) {
            if (level != null && el.tagName() == "h$level") {
                flush()
                title = el.text().takeIf { it.isNotBlank() }
            }
            current.add(el)
        }
        flush()
        return result
    }

    private fun xhtml(elements: List<org.jsoup.nodes.Element>): String {
        val doc: Document = Jsoup.parseBodyFragment(elements.joinToString("") { it.outerHtml() })
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        return doc.body().html()
    }

    // ---- EPUB package ----

    private fun write(chapters: List<Chapter>, title: String, source: String, out: OutputStream, lang: String) {
        val id = "urn:uuid:" + UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8))
        val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        fun href(i: Int) = "c%03d.xhtml".format(i + 1)

        val manifest = StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        val spine = StringBuilder()
        chapters.indices.forEach { i ->
            manifest.append("<item id=\"c${i + 1}\" href=\"${href(i)}\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"c${i + 1}\"/>")
        }
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">$id</dc:identifier><dc:title>${esc(title)}</dc:title><dc:language>$lang</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val items = chapters.indices.joinToString("") { "<li><a href=\"${href(it)}\">${esc(chapters[it].title)}</a></li>" }
        // The book's own words, in its language, as the pack builder writes them ("Índice" in Spanish).
        val contents = if (lang == "es") "Índice" else "Contents"
        val nav = page(title, "<nav epub:type=\"toc\" id=\"toc\"><h1>$contents</h1><ol>$items</ol></nav>", lang)

        ZipOutputStream(out).use { z ->
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            z.write(mime)
            z.closeEntry()
            fun put(path: String, content: String) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", opf)
            put("OEBPS/nav.xhtml", nav)
            chapters.forEachIndexed { i, c -> put("OEBPS/${href(i)}", page("${title} - ${c.title}", c.body, lang)) }
        }
    }

    private fun page(title: String, body: String, lang: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang"><head><meta charset="utf-8"/><title>${esc(title)}</title></head><body>$body</body></html>"""
}
