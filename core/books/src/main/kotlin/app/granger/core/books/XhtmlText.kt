package app.granger.core.books

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

data class TextChunk(val href: String, val ordinal: Int, val progression: Double, val text: String)

/** Splits a chapter into search-sized text chunks. Output is deterministic so snippets can re-extract by (href, ordinal). */
object XhtmlText {
    private const val MIN_CHUNK = 400
    private const val MAX_CHUNK = 2000
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, td, dd, dt, div"
    private val WHITESPACE = Regex("\\s+")

    /** Empty for a missing, corrupt or oversized resource. */
    fun chunks(zip: ZipFile, href: String): List<TextChunk> {
        val entry = zip.getEntry(href) ?: return emptyList()
        return try {
            chunksOf(href, readCapped(zip, entry, XHTML_LIMIT))
        } catch (_: IOException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /** Chunks of every readable spine resource; missing or corrupt ones are skipped. */
    fun all(file: File, spine: List<String>): Sequence<TextChunk> = sequence {
        val zip = try { ZipFile(file) } catch (_: IOException) { null } ?: return@sequence
        zip.use { for (href in spine) yieldAll(chunks(it, href)) }
    }

    private fun chunksOf(href: String, bytes: ByteArray): List<TextChunk> {
        val doc = Jsoup.parse(bytes.inputStream(), "UTF-8", "", Parser.xmlParser())

        val texts = ArrayList<String>()
        var buffer = ""
        for (block in doc.select(BLOCKS)) {
            val own = blockText(block)
            if (own.isEmpty()) continue
            for (piece in cutAtSpaces(own, MAX_CHUNK)) {
                buffer = if (buffer.isEmpty()) piece else "$buffer\n$piece"
                if (buffer.length > MAX_CHUNK) {
                    val parts = cutAtSpaces(buffer, MAX_CHUNK)
                    texts.addAll(parts.dropLast(1))
                    buffer = parts.last()
                }
                if (buffer.length >= MIN_CHUNK) {
                    texts.add(buffer)
                    buffer = ""
                }
            }
        }
        if (buffer.isNotEmpty()) texts.add(buffer)

        val total = texts.sumOf { it.length }.toDouble()
        var before = 0
        return texts.mapIndexed { i, t ->
            TextChunk(href, i, before / total, t).also { before += t.length }
        }
    }

    /** Innermost blocks give their full text; containers give their text minus nested blocks. */
    private fun blockText(el: Element): String {
        if (el.select(BLOCKS).size <= 1) return el.text().trim()
        val sb = StringBuilder()
        for (node in el.childNodes()) {
            when {
                node is TextNode -> sb.append(node.text())
                node is Element && node.selectFirst(BLOCKS) == null -> sb.append(node.text())
            }
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }
}
