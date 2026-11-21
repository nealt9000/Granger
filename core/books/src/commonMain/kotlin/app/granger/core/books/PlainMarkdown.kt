package app.granger.core.books

/**
 * A small Markdown reader for importing `.md` books where commonmark-java and jsoup don't run (the iPhone; Plan I5
 * Task 6). It keeps Android's chapter rules ([MarkdownChapters], `CommonmarkChapters`):
 * - chapters start at the shallowest heading level used twice or more at the top level; each is titled by that heading,
 *   else by its first heading, else "Part N";
 * - pictures are dropped, raw HTML is shown as text, and a link keeps its address only when it's http, https, mailto,
 *   data or relative (commonmark's sanitiser);
 * - each chapter's body is well-formed XHTML (`<hr />`, `<br />`, `&amp;`), as jsoup writes it.
 *
 * It reads the Markdown people write: ATX and setext headings, paragraphs, emphasis and strong, code spans and blocks
 * (fenced and indented), links and autolinks, block quotes, bullet and numbered lists (nested by indent), rules and
 * hard breaks. Reference-style links and tables read as plain text.
 */
object PlainMarkdown : MarkdownChapters {
    override fun chapters(markdown: String): List<Pair<String, String>> {
        val blocks = parse(markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n'))
        val counts = blocks.filterIsInstance<Block.Heading>().groupingBy { it.level }.eachCount()
        val level = counts.filterValues { it >= 2 }.keys.minOrNull()
        val result = ArrayList<Pair<String, String>>()
        var title: String? = null
        val current = ArrayList<Block>()
        fun flush() {
            if (current.isEmpty()) return
            val t = title ?: current.firstNotNullOfOrNull { (it as? Block.Heading)?.let { h -> plain(h.text).ifBlank { null } } }
            result.add((t ?: "Part ${result.size + 1}") to current.joinToString("\n") { render(it) })
            current.clear()
            title = null
        }
        for (b in blocks) {
            if (level != null && b is Block.Heading && b.level == level) {
                flush()
                title = plain(b.text).ifBlank { null }
            }
            current.add(b)
        }
        flush()
        return result
    }

    private sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Para(val text: String) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
        data class Quote(val children: List<Block>) : Block
        data class Items(val ordered: Boolean, val start: Int, val items: List<List<Block>>, val tight: Boolean) : Block
    }

    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val ATX = Regex("^ {0,3}(#{1,6})(?:[ ]+(.*?))?(?:[ ]+#+)?[ ]*$")
    private val RULE = Regex("^ {0,3}(?:(?:\\*[ ]*){3,}|(?:-[ ]*){3,}|(?:_[ ]*){3,})$")
    private val QUOTE = Regex("^ {0,3}> ?(.*)$")
    private val ITEM = Regex("^( {0,3})([-+*]|\\d{1,9}[.)])( {1,4}|$)(.*)$")
    private val SETEXT_1 = Regex("^ {0,3}=+[ ]*$")
    private val SETEXT_2 = Regex("^ {0,3}-+[ ]*$")

    private fun startsBlock(line: String): Boolean =
        FENCE.matches(line) || ATX.matches(line) || RULE.matches(line) || QUOTE.matches(line) || ITEM.matches(line)

    private fun parse(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            FENCE.matchEntire(line)?.let { m ->
                val fence = m.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !(lines[i].trimStart().startsWith(fence) && lines[i].trim().all { it == fence[0] })) body.add(lines[i++])
                i++
                out.add(Block.Code(body.joinToString("\n")))
                return@let
            }?.let { continue }
            ATX.matchEntire(line)?.let { m ->
                out.add(Block.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                i++
            }?.let { continue }
            if (RULE.matches(line)) { out.add(Block.Rule); i++; continue }
            if (QUOTE.matches(line)) {
                val inner = ArrayList<String>()
                while (i < lines.size && QUOTE.matches(lines[i])) inner.add(QUOTE.matchEntire(lines[i])!!.groupValues[1]).also { i++ }
                out.add(Block.Quote(parse(inner)))
                continue
            }
            ITEM.matchEntire(line)?.let { first ->
                i = list(lines, i, first, out)
            }?.let { continue }
            if (line.startsWith("    ")) {
                val body = ArrayList<String>()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) body.add(lines[i++].removePrefix("    "))
                out.add(Block.Code(body.joinToString("\n").trimEnd('\n')))
                continue
            }
            val para = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && (para.isEmpty() || !startsBlock(lines[i]) || SETEXT_2.matches(lines[i]))) {
                val l = lines[i]
                if (para.isNotEmpty() && (SETEXT_1.matches(l) || SETEXT_2.matches(l))) {
                    out.add(Block.Heading(if (SETEXT_1.matches(l)) 1 else 2, para.joinToString(" ") { it.trim() }))
                    para.clear()
                    i++
                    break
                }
                para.add(l)
                i++
            }
            if (para.isNotEmpty()) out.add(Block.Para(para.joinToString("\n") { it.trimStart() }.trimEnd()))
        }
        return out
    }

    /** A list from [lines] at [start] (whose first item is [first]) into [out]; the line after it. */
    private fun list(lines: List<String>, start: Int, first: MatchResult, out: MutableList<Block>): Int {
        val marker = first.groupValues[2]
        val ordered = marker.first().isDigit()
        val delimiter = if (ordered) marker.last() else marker.first()
        fun sameList(m: MatchResult): Boolean {
            val k = m.groupValues[2]
            return if (ordered) k.first().isDigit() && k.last() == delimiter else k.first() == delimiter
        }
        val items = ArrayList<List<String>>()
        var tight = true
        var i = start
        while (i < lines.size) {
            val m = ITEM.matchEntire(lines[i])?.takeIf(::sameList) ?: break
            val offset = m.groupValues[1].length + m.groupValues[2].length + m.groupValues[3].length.coerceAtLeast(1)
            val item = arrayListOf(m.groupValues[4])
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                    val nl = lines[next]
                    val indented = nl.length - nl.trimStart().length >= offset
                    val sibling = ITEM.matchEntire(nl)?.let(::sameList) == true
                    if (!indented && !sibling) break
                    tight = false
                    if (sibling && !indented) { i = next; break }
                    item.add("")
                    i++
                    continue
                }
                val indent = l.length - l.trimStart().length
                when {
                    indent >= offset -> item.add(l.substring(offset))
                    ITEM.matches(l) || startsBlock(l) -> break
                    item.lastOrNull()?.isNotBlank() == true -> item.add(l.trimStart())  // a lazy continuation line
                    else -> break
                }
                i++
            }
            items.add(item)
        }
        out.add(Block.Items(ordered, if (ordered) marker.dropLast(1).toIntOrNull() ?: 1 else 1, items.map { parse(it) }, tight))
        return i
    }

    private fun render(b: Block): String = when (b) {
        is Block.Heading -> "<h${b.level}>${inline(b.text)}</h${b.level}>"
        is Block.Para -> "<p>${inline(b.text)}</p>"
        is Block.Code -> "<pre><code>${escape(b.text)}${if (b.text.isEmpty()) "" else "\n"}</code></pre>"
        Block.Rule -> "<hr />"
        is Block.Quote -> "<blockquote>\n${b.children.joinToString("\n") { render(it) }}\n</blockquote>"
        is Block.Items -> {
            val tag = if (b.ordered) "ol" else "ul"
            val open = if (b.ordered && b.start != 1) "<ol start=\"${b.start}\">" else "<$tag>"
            open + "\n" + b.items.joinToString("\n") { item ->
                val inner = if (b.tight) item.joinToString("\n") { if (it is Block.Para) inline(it.text) else render(it) }
                else item.joinToString("\n") { render(it) }
                "<li>$inner</li>"
            } + "\n</$tag>"
        }
    }

    private const val OPEN = ''
    private const val CLOSE = ''
    private val CODE_SPAN = Regex("(`+)(.+?)\\1", RegexOption.DOT_MATCHES_ALL)
    private val BACKSLASH = Regex("\\\\([!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~])")
    private val AUTOLINK = Regex("<((?:https?|mailto):[^\\s<>]+)>", RegexOption.IGNORE_CASE)
    // A link's address may hold balanced brackets, as commonmark allows: (javascript:alert(1)).
    private const val DEST = "((?:[^()\\s<>]|\\([^()\\s]*\\))*)"
    private val IMAGE = Regex("!\\[[^\\]]*]\\(\\s*<?$DEST>?(?:\\s+\"[^\"]*\")?\\s*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\(\\s*<?$DEST>?(?:\\s+\"([^\"]*)\")?\\s*\\)")
    private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val EM_STAR = Regex("\\*(?=\\S)(.+?)(?<=\\S)\\*")
    private val EM_UNDER = Regex("(?<![A-Za-z0-9])_(?=\\S)(.+?)(?<=\\S)_(?![A-Za-z0-9])")
    private val HARD_BREAK = Regex("(?: {2,}|\\\\)\n")
    private val PLACEHOLDER = Regex("$OPEN(\\d+)$CLOSE")
    private val SAFE_SCHEMES = setOf("http", "https", "mailto", "data")

    /** A line's inline Markdown as XHTML. */
    private fun inline(text: String): String {
        val held = ArrayList<String>()
        fun hold(html: String): String { held.add(html); return "$OPEN${held.size - 1}$CLOSE" }
        var s = text.replace(OPEN, ' ').replace(CLOSE, ' ')
        s = CODE_SPAN.replace(s) { hold("<code>${escape(it.groupValues[2].trim())}</code>") }
        s = HARD_BREAK.replace(s) { hold("<br />") + "\n" }
        s = BACKSLASH.replace(s) { hold(escape(it.groupValues[1])) }
        s = AUTOLINK.replace(s) { hold("<a href=\"${attr(sanitise(it.groupValues[1]))}\">${escape(it.groupValues[1])}</a>") }
        s = IMAGE.replace(s, "")
        s = LINK.replace(s) { m ->
            val title = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { " title=\"${attr(it)}\"" }.orEmpty()
            hold("<a href=\"${attr(sanitise(m.groupValues[2]))}\"$title>${inline(m.groupValues[1])}</a>")
        }
        s = escape(s)
        s = STRONG.replace(s) { "<strong>${it.groupValues[2]}</strong>" }
        s = EM_STAR.replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = EM_UNDER.replace(s) { "<em>${it.groupValues[1]}</em>" }
        repeat(3) { s = PLACEHOLDER.replace(s) { held[it.groupValues[1].toInt()] } }
        return s
    }

    /** A heading's words as text (its tags gone, its entities read). */
    private fun plain(text: String): String =
        inline(text).replace(Regex("<[^>]+>"), "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()

    /** commonmark's sanitiser: an address with any other scheme becomes empty. */
    private fun sanitise(url: String): String {
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return url
        return if (scheme in SAFE_SCHEMES) url else ""
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(s: String) = escape(s).replace("\"", "&quot;")
}
