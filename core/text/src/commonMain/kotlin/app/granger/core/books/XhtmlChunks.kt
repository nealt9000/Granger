package app.granger.core.books

import app.granger.core.text.Chunkers
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

data class TextChunk(val href: String, val ordinal: Int, val progression: Double, val text: String)

/**
 * Splits one chapter's XHTML into search-sized text chunks. Output is deterministic so snippets can re-extract by
 * (href, ordinal), and pack-time vectors (`tools/chunker`) key on the same chunks. Any change to the output must bump
 * [app.granger.core.text.Chunkers.VERSION].
 */
object XhtmlChunks {
    private const val MIN_CHUNK = 400
    private const val MAX_CHUNK = 2000
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, td, dd, dt, div"
    private val WHITESPACE = Regex("[${Chunkers.SPACE}]+")

    fun of(href: String, bytes: ByteArray): List<TextChunk> {
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
