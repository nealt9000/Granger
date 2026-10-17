package app.granger.core.books

import java.io.File
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class TextChunk(val href: String, val ordinal: Int, val progression: Double, val text: String)

/** Splits a chapter into search-sized text chunks. Output is deterministic so snippets can re-extract by (href, ordinal). */
object XhtmlText {
    private const val MIN_CHUNK = 400
    private const val MAX_CHUNK = 2000
    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, pre, td, dd, dt, div"

    fun chunks(zip: ZipFile, href: String): List<TextChunk> {
        val entry = zip.getEntry(href) ?: return emptyList()
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        val body = doc.body()

        val texts = ArrayList<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) texts.add(buffer.toString())
            buffer.setLength(0)
        }
        for (block in body.select(BLOCKS)) {
            val own = blockText(block)
            if (own.isEmpty()) continue
            for (piece in cut(own)) {
                if (buffer.isNotEmpty() && buffer.length + 1 + piece.length > MAX_CHUNK) flush()
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(piece)
                if (buffer.length >= MIN_CHUNK) flush()
            }
        }
        flush()

        val total = texts.sumOf { it.length }.toDouble()
        var before = 0
        return texts.mapIndexed { i, t ->
            TextChunk(href, i, before / total, t).also { before += t.length }
        }
    }

    fun all(file: File, spine: List<String>): Sequence<TextChunk> = sequence {
        ZipFile(file).use { zip -> for (href in spine) yieldAll(chunks(zip, href)) }
    }

    /** Innermost blocks give their full text; containers of other blocks give only their own text. */
    private fun blockText(el: Element): String {
        val container = el.select(BLOCKS).size > 1
        return (if (container) el.ownText() else el.text()).trim()
    }

    private fun cut(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (text.length - start > MAX_CHUNK) {
            var end = text.lastIndexOf(' ', start + MAX_CHUNK)
            if (end <= start) end = start + MAX_CHUNK
            out.add(text.substring(start, end).trim())
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        if (start < text.length) out.add(text.substring(start))
        return out.filter { it.isNotEmpty() }
    }
}
