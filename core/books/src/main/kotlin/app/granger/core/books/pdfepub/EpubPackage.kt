package app.granger.core.books.pdfepub

import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An EPUB 3's parts: chapters with nested contents, pictures, a stylesheet and a cover (Plan IA converter stage 9). */
internal class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    /** Names the book's identifier, so the same source always gets the same one. */
    val source: String,
    val chapters: List<EpubChapter>,
    val images: List<EpubImage>,
    val css: String,
    /** One of [images]' paths. */
    val coverPath: String?,
)

/** A chapter's XHTML [body], and its [sections] (title, element id) for the contents. */
internal class EpubChapter(val title: String, val body: String, val sections: List<Pair<String, String>>)

/** [path] is inside `OEBPS/`, such as `images/p12-1.jpg`. */
internal class EpubImage(val path: String, val mediaType: String, val bytes: ByteArray)

internal object EpubPackage {
    fun href(i: Int) = "c%03d.xhtml".format(i + 1)

    fun write(book: EpubBook, out: OutputStream) {
        val id = "urn:uuid:" + UUID.nameUUIDFromBytes(book.source.toByteArray(Charsets.UTF_8))
        val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val lang = book.language.ifBlank { "en" }

        val manifest = StringBuilder()
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        manifest.append("<item id=\"css\" href=\"book.css\" media-type=\"text/css\"/>")
        val spine = StringBuilder()
        book.chapters.indices.forEach { i ->
            manifest.append("<item id=\"c${i + 1}\" href=\"${href(i)}\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"c${i + 1}\"/>")
        }
        book.images.forEachIndexed { i, image ->
            val cover = if (image.path == book.coverPath) " properties=\"cover-image\"" else ""
            manifest.append("<item id=\"img${i + 1}\" href=\"${esc(image.path)}\" media-type=\"${image.mediaType}\"$cover/>")
        }
        val author = if (book.author.isNotBlank()) "<dc:creator>${esc(book.author)}</dc:creator>" else ""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="bookid">$id</dc:identifier><dc:title>${esc(book.title)}</dc:title>$author<dc:language>$lang</dc:language><meta property="dcterms:modified">$modified</meta></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val items = book.chapters.withIndex().joinToString("") { (i, c) ->
            val sub = if (c.sections.isEmpty()) "" else
                "<ol>" + c.sections.joinToString("") { (t, anchor) -> "<li><a href=\"${href(i)}#$anchor\">${esc(t)}</a></li>" } + "</ol>"
            "<li><a href=\"${href(i)}\">${esc(c.title)}</a>$sub</li>"
        }
        val nav = page(book.title, "<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>$items</ol></nav>", lang)

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
            fun put(path: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(path))
                z.write(bytes)
                z.closeEntry()
            }
            fun put(path: String, content: String) = put(path, content.toByteArray(Charsets.UTF_8))
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", opf)
            put("OEBPS/nav.xhtml", nav)
            put("OEBPS/book.css", book.css)
            book.chapters.forEachIndexed { i, c -> put("OEBPS/${href(i)}", page(c.title, c.body, lang)) }
            book.images.forEach { put("OEBPS/${it.path}", it.bytes) }
        }
    }

    private fun page(title: String, body: String, lang: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang"><head><meta charset="utf-8"/><title>${esc(title)}</title><link rel="stylesheet" type="text/css" href="book.css"/></head><body>$body</body></html>"""

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t') Unit else append(c)
        }
    }
}
