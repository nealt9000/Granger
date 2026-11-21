package app.granger.core.library

/**
 * A pack with no manifest that is plainly a container of books (Plan KB2 Task 2.2): listed by its files, each titled by
 * its file name.
 */
object BookFiles {
    /** What a pack's books can be. */
    val MIMES = setOf("application/pdf", "application/epub+zip")

    /**
     * The audit's rule for a pure container, from a ZIM's `Counter` metadata: at least 3 PDFs and EPUBs, and no more than
     * 10 HTML pages, which only wrap the files. Deliberately not looser: the World Factbook (839 PDFs, 830 pages) is a
     * website.
     */
    fun isContainer(counter: String): Boolean {
        val counts = parseCounter(counter)
        val docs = MIMES.sumOf { counts[it] ?: 0 }
        val html = counts.filterKeys { it.startsWith("text/html") }.values.sum()
        return docs >= 3 && html <= 10
    }

    /** `text/html=12;image/png=3` as MIME type to count; a malformed part is skipped. */
    fun parseCounter(counter: String): Map<String, Int> = buildMap {
        for (part in counter.split(';')) {
            val at = part.lastIndexOf('=')
            if (at <= 0) continue
            val mime = part.substring(0, at).trim()
            part.substring(at + 1).trim().toIntOrNull()?.let { put(mime, (get(mime) ?: 0) + it) }
        }
    }

    /**
     * A title from an entry's path: its file name without query or extension, `%xx` decoded, underscores as spaces
     * (`files/A_Treatise%20on_Hedges.pdf` → "A Treatise on Hedges").
     */
    fun titleOf(path: String): String {
        val name = path.substringBefore('?').substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return percentDecode(stem).replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { name }
    }

    /** `%xx` sequences as UTF-8; a `+` stays a `+` (a path, not a form), and a broken sequence is kept as it is. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = ArrayList<Byte>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.add(byte.toByte())
                    i += 3
                    continue
                }
            }
            out.addAll(ch.toString().encodeToByteArray().toList())
            i++
        }
        return out.toByteArray().decodeToString()
    }

    fun formatOf(mime: String): BookFormat? = when (mime) {
        "application/pdf" -> BookFormat.PDF
        "application/epub+zip" -> BookFormat.EPUB
        else -> null
    }
}
