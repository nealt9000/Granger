package app.granger.core.library

/**
 * One record of a nautilus pack's `database.js`: a document, its title (`ti`), description (`dsc`), author (`aut`) and
 * files (`fp`, each at `files/<fp>` in the pack), [order] its place in the list. The 9 packs in Kiwix's catalog
 * (2026-10-30) give every record all five keys and one file; a blank or `-` author means none.
 */
data class NautilusRecord(
    val id: String,
    val title: String,
    val description: String,
    val author: String,
    val files: List<String>,
    val order: Int,
)

/**
 * The document list of a Kiwix "nautilus" pack (the zimgit-* and prunelle packs). The pack lists its documents from
 * JavaScript, in an entry `database.js`: `var DATABASE = [{'_id': '00001', 'ti': 'Camping Recipes', 'dsc': '…',
 * 'aut': 'Various', 'fp': ['Food Preparation (2).pdf']}, …]`, Python-style dicts with single- or double-quoted strings.
 * Each document's file is at `files/<fp>` in the pack, and its `ti` is the title the pack's own page shows, which beats
 * the PDF's metadata and the link's words.
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
        for (record in records(text)) record.files.forEach { titles.putIfAbsent(it, record.title) }
        return titles
    }

    /**
     * The records of [text], in order, each with a title and at least one file; strings trimmed, an author of `-`
     * blank. Read leniently, like [parse].
     */
    fun records(text: String): List<NautilusRecord> {
        val out = ArrayList<NautilusRecord>()
        val scanner = Scanner(text)
        var depth = 0
        val fields = HashMap<String, String>()
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
                            key?.let { fields.putIfAbsent(it, s) }
                            expectingValue = false
                            key = null
                        }
                        depth == 1 -> key = s
                    }
                }
                '{' -> {
                    depth++
                    if (depth == 1) {
                        fields.clear()
                        files.clear()
                        key = null
                        expectingValue = false
                    }
                }
                '}' -> {
                    if (depth == 1) {
                        val title = fields["ti"]?.trim().orEmpty()
                        val kept = files.filter { it.isNotBlank() }
                        if (title.isNotEmpty() && kept.isNotEmpty()) {
                            out += NautilusRecord(
                                id = fields["_id"]?.trim().orEmpty(),
                                title = title,
                                description = fields["dsc"]?.trim().orEmpty(),
                                author = fields["aut"]?.trim()?.takeUnless { it == "-" }.orEmpty(),
                                files = kept,
                                order = out.size,
                            )
                        }
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
        return out
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
