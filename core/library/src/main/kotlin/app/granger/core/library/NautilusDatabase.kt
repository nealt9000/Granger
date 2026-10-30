package app.granger.core.library

/**
 * The document list of a Kiwix "nautilus" pack (the zimgit-* packs). The pack lists its documents from JavaScript, in
 * an entry `database.js`: `var DATABASE = [{'_id': '00001', 'ti': 'Camping Recipes', 'fp': ['Food Preparation (2).pdf']}, …]`,
 * Python-style dicts with single- or double-quoted strings. Each document's file is at `files/<fp>` in the pack, and
 * its `ti` is the title the pack's own page shows, which beats the PDF's metadata and the link's words.
 */
object NautilusDatabase {
    /** The entry the list is in. */
    const val PATH = "database.js"

    /** Where a listed file is in the pack: `files/` and its `fp`. */
    const val FILES = "files/"

    /**
     * Each listed file name (an `fp`, as it is after `files/`) with its document's title (`ti`). Read leniently: a
     * record without a title or files is skipped, and text that isn't a list gives an empty map, never an error.
     */
    fun parse(text: String): Map<String, String> {
        val titles = HashMap<String, String>()
        val scanner = Scanner(text)
        var depth = 0
        var title: String? = null
        val files = mutableListOf<String>()
        // The last string read at the record's own level, a key once a ':' follows it.
        var key: String? = null
        var expectingValue = false
        var inFiles = false
        while (true) {
            val c = scanner.next() ?: break
            when (c) {
                '\'', '"' -> {
                    val s = scanner.string(c) ?: break
                    when {
                        inFiles -> files += s
                        depth == 1 && expectingValue -> {
                            if (key == "ti") title = s
                            expectingValue = false
                            key = null
                        }
                        depth == 1 -> key = s
                    }
                }
                '{' -> {
                    depth++
                    if (depth == 1) {
                        title = null
                        files.clear()
                        key = null
                        expectingValue = false
                    }
                }
                '}' -> {
                    if (depth == 1) {
                        val name = title?.trim().orEmpty()
                        if (name.isNotEmpty()) files.forEach { titles.putIfAbsent(it, name) }
                    }
                    if (depth > 0) depth--
                    inFiles = false
                }
                ':' -> if (depth == 1 && key != null) expectingValue = true
                '[' -> if (depth == 1 && expectingValue) {
                    inFiles = key == "fp"
                    expectingValue = false
                }
                ']' -> if (inFiles) {
                    inFiles = false
                    key = null
                }
                ',' -> if (depth == 1 && !inFiles) {
                    key = null
                    expectingValue = false
                }
            }
        }
        return titles
    }

    /** Reads [text] a character at a time, and a quoted string with its escapes. */
    private class Scanner(private val text: String) {
        private var at = 0

        fun next(): Char? = if (at < text.length) text[at++] else null

        /** The string up to the closing [quote], escapes undone; null if it never closes. */
        fun string(quote: Char): String? {
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == quote -> return out.toString()
                    c == '\\' && at < text.length -> {
                        val e = text[at++]
                        when (e) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'u' -> {
                                val hex = text.substring(at, minOf(at + 4, text.length))
                                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                                if (code != null) {
                                    out.append(code.toChar())
                                    at += 4
                                } else {
                                    out.append('u')
                                }
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
            return null
        }
    }
}
