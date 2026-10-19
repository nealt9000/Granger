package app.granger.core.research

/** [medical] is null when the reply has no `medical:` line (it can be cut off by the token cap). */
data class RewriteResult(val keywords: List<String>, val medical: Boolean?)

/** Turns a question into catalogue keywords with one short model call. */
object Rewrite {
    private val KEYWORDS_LINE = Regex("^\\s*keywords\\s*:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val MEDICAL_LINE = Regex("^\\s*medical\\s*:\\s*(yes|no)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private const val MAX_KEYWORDS = 6
    private val SPACES = Regex("\\s+")
    private val META_WORDS = Regex(
        "\\b(definitions?|meanings?|etymology|origins?|history|facts|references?|problems|explanation|overview|information|info|scriptures?|teachings|texts?|passages?|quotes?|quotations?|excerpts?)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun prompt(question: String, previousQuestion: String?): String = buildString {
        append("Turn the question into search words for finding the text that answers it in a library.\n")
        append("Use the words that text itself would contain: names of people, places, works and things, and the source's own wording (older or formal words too).\n")
        append("Don't use words about the question, like definition, meaning, history, facts, problems, references, etymology.\n")
        append("For what a word means or where it comes from, give the word itself.\n")
        append("Reply in exactly two lines:\n")
        append("keywords: <3 to 6 words or short names, comma-separated>\n")
        append("medical: <yes if the question is about health, illness, injury, the body, medicines or treatment; otherwise no>\n")
        append("Example:\n")
        append("Question: how did old sailors work out how far east or west they were\n")
        append("keywords: longitude, marine chronometer, lunar distance, navigation\n")
        append("medical: no\n")
        if (previousQuestion != null) append("Previous question: ").append(previousQuestion).append('\n')
        append("Question: ").append(question)
    }

    /** Null when the reply has no `keywords:` line with at least one keyword. */
    fun parse(reply: String): RewriteResult? {
        val line = KEYWORDS_LINE.find(reply)?.groupValues?.get(1) ?: return null
        // The model sometimes echoes the prompt's placeholder ("<3 to 6 keywords ...>") instead of keywords.
        if (line.trim().startsWith("<") || line.contains("comma-separated", ignoreCase = true)) return null
        val original = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (original.isEmpty()) return null
        val cleaned = original
            .map { META_WORDS.replace(it, " ").replace(SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val keywords = (cleaned.ifEmpty { original }).take(MAX_KEYWORDS)
        val medical = MEDICAL_LINE.find(reply)?.groupValues?.get(1)?.equals("yes", ignoreCase = true)
        return RewriteResult(keywords, medical)
    }
}
