package app.granger.core.research

/** [medical] is null when the reply has no `medical:` line (it can be cut off by the token cap). */
data class RewriteResult(val keywords: List<String>, val medical: Boolean?)

/** Turns a question into catalogue keywords with one short model call. */
object Rewrite {
    private val KEYWORDS_LINE = Regex("^\\s*keywords\\s*:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val MEDICAL_LINE = Regex("^\\s*medical\\s*:\\s*(yes|no)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private const val MAX_KEYWORDS = 6
    private const val MAX_ALTERNATES = 2
    private val BRACKETS = Regex("\\(([^)]*)\\)?")
    private val SPACES = Regex("\\s+")
    private const val META = "(definitions?|meanings?|etymology|origins?|history|facts|references?|problems|explanation|overview|information|info|scriptures?|teachings|texts?|passages?|quotes?|quotations?|excerpts?)"
    /**
     * A word about the question at either end of a keyword ("betimes meaning", "origins of chess"); one inside a name
     * stays ("on the origin of species": r1-dense-1 searched "on the of species").
     */
    private val META_WORDS = Regex("^$META(\\s+(of|for)\\b)?\\s*|\\s*\\b$META$", RegexOption.IGNORE_CASE)

    /**
     * With [alternates] (Plan R0's debug variant), a keyword may carry up to [MAX_ALTERNATES] other words the source
     * might use for it, in brackets; [parse] turns `wine (strong drink)` into the group `wine|strong drink`.
     */
    fun prompt(question: String, previousQuestion: String?, alternates: Boolean = false): String = buildString {
        append("Turn the question into search words for finding the text that answers it in a library.\n")
        append("Use the words that text itself would contain: names of people, places, works and things, and the source's own wording (older or formal words too).\n")
        append("Don't use words about the question, like definition, meaning, history, facts, problems, references, etymology.\n")
        append("For what a word means or where it comes from, give the word itself.\n")
        if (alternates) {
            append("After a keyword, add in brackets up to 2 other words the source itself might use for it instead (older words, synonyms, the thing named differently).\n")
        }
        append("Reply in exactly two lines:\n")
        append(if (alternates) "keywords: <3 to 6 words or short names, comma-separated, each with any other words in brackets>\n" else "keywords: <3 to 6 words or short names, comma-separated>\n")
        append("medical: <yes if the question is about health, illness, injury, the body, medicines or treatment; otherwise no>\n")
        append("Example:\n")
        append("Question: how did old sailors work out how far east or west they were\n")
        append(if (alternates) "keywords: longitude (meridian), marine chronometer (timekeeper), lunar distance, navigation (reckoning)\n" else "keywords: longitude, marine chronometer, lunar distance, navigation\n")
        append("medical: no\n")
        if (previousQuestion != null) append("Previous question: ").append(previousQuestion).append('\n')
        append("Question: ").append(question)
    }

    /**
     * Null when the reply has no `keywords:` line with at least one keyword. With [alternates], a keyword's bracketed
     * words become one group, `keyword|other|other` (see [Keywords.searchTerms] and [LexicalScore]).
     */
    fun parse(reply: String, alternates: Boolean = false): RewriteResult? {
        val line = KEYWORDS_LINE.find(reply)?.groupValues?.get(1) ?: return null
        // The model sometimes echoes the prompt's placeholder ("<3 to 6 keywords ...>") instead of keywords.
        if (line.trim().startsWith("<") || line.contains("comma-separated", ignoreCase = true)) return null
        val original = (if (alternates) splitOutsideBrackets(line) else line.split(',')).map { it.trim() }.filter { it.isNotEmpty() }
        if (original.isEmpty()) return null
        fun clean(k: String): String {
            var out = k.replace(SPACES, " ").trim()
            while (true) out = META_WORDS.replace(out, "").trim().takeIf { it != out } ?: return out
        }
        val cleaned = original
            .map { k ->
                if (!alternates) return@map clean(k)
                val head = clean(k.substringBefore('(').replace(")", ""))
                val others = BRACKETS.find(k)?.groupValues?.get(1).orEmpty().split(',').map(::clean)
                    .filter { it.isNotEmpty() && !it.equals(head, ignoreCase = true) }.take(MAX_ALTERNATES)
                (listOf(head) + others).filter { it.isNotEmpty() }.joinToString("|")
            }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val keywords = (cleaned.ifEmpty { original }).take(MAX_KEYWORDS)
        val medical = MEDICAL_LINE.find(reply)?.groupValues?.get(1)?.equals("yes", ignoreCase = true)
        return RewriteResult(keywords, medical)
    }

    /** Splits on commas that aren't inside brackets: "a (b, c), d" is "a (b, c)" and "d". An unclosed bracket runs to the end. */
    private fun splitOutsideBrackets(line: String): List<String> {
        val out = mutableListOf<String>()
        val part = StringBuilder()
        var depth = 0
        for (ch in line) {
            when {
                ch == '(' -> { depth++; part.append(ch) }
                ch == ')' -> { if (depth > 0) depth--; part.append(ch) }
                ch == ',' && depth == 0 -> { out += part.toString(); part.clear() }
                else -> part.append(ch)
            }
        }
        out += part.toString()
        return out
    }
}
