package app.granger.core.research

/** [medical] is null when the reply has no `medical:` line (it can be cut off by the token cap). */
data class RewriteResult(val keywords: List<String>, val medical: Boolean?)

/** Turns a question into catalogue keywords with one short model call. */
object Rewrite {
    private val KEYWORDS_LINE = Regex("^\\s*keywords\\s*:(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val MEDICAL_LINE = Regex("^\\s*medical\\s*:\\s*(yes|no)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private const val MAX_KEYWORDS = 6

    fun prompt(question: String, previousQuestion: String?): String = buildString {
        append("Turn the question into search keywords for a library catalogue.\n")
        append("Reply in exactly two lines:\n")
        append("keywords: <3 to 6 keywords or short phrases, comma-separated>\n")
        append("medical: <yes or no>\n")
        if (previousQuestion != null) append("Previous question: ").append(previousQuestion).append('\n')
        append("Question: ").append(question)
    }

    /** Null when the reply has no `keywords:` line with at least one keyword. */
    fun parse(reply: String): RewriteResult? {
        val line = KEYWORDS_LINE.find(reply)?.groupValues?.get(1) ?: return null
        // The model sometimes echoes the prompt's placeholder ("<3 to 6 keywords ...>") instead of keywords.
        if (line.trim().startsWith("<") || line.contains("comma-separated", ignoreCase = true)) return null
        val keywords = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_KEYWORDS)
        if (keywords.isEmpty()) return null
        val medical = MEDICAL_LINE.find(reply)?.groupValues?.get(1)?.equals("yes", ignoreCase = true)
        return RewriteResult(keywords, medical)
    }
}
