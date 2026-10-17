package app.granger.core.books

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

    fun convert(text: String, title: String, markdown: Boolean, out: OutputStream, maxChapterChars: Int = 100_000) {
        val chapters = (if (markdown) markdownChapters(text) else txtChapters(text, maxChapterChars))
            .ifEmpty { listOf(Chapter("Part 1", "")) }
        write(chapters, title, text, out)
    }

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

    private fun paragraphList(text: String): List<String> =
        text.split(BLANK_LINES).map { it.trim() }.filter { it.isNotEmpty() }

    private fun paragraphs(text: String): String {
        val sb = StringBuilder()
        for (p in paragraphList(text)) sb.append("<p>").append(esc(p)).append("</p>")
        return sb.toString()
    }

    private fun cutAtSpaces(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (text.length - start > max) {
            var end = text.lastIndexOf(' ', start + max)
            if (end <= start) end = start + max
            out.add(text.substring(start, end).trim())
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        if (start < text.length) out.add(text.substring(start))
        return out.filter { it.isNotEmpty() }
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

        val html = HtmlRenderer.builder().escapeHtml(true).build().render(doc)
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

    private fun write(chapters: List<Chapter>, title: String, source: String, out: OutputStream) {
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
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="en"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">$id</dc:identifier><dc:title>${esc(title)}</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val items = chapters.indices.joinToString("") { "<li><a href=\"${href(it)}\">${esc(chapters[it].title)}</a></li>" }
        val nav = page(title, "<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>$items</ol></nav>")

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
            chapters.forEachIndexed { i, c -> put("OEBPS/${href(i)}", page("${title} - ${c.title}", c.body)) }
        }
    }

    private fun page(title: String, body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><meta charset="utf-8"/><title>${esc(title)}</title></head><body>$body</body></html>"""

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
