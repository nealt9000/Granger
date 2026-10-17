package app.granger.core.research

/** The previous question and answer, for a follow-up. */
data class PriorTurn(val question: String, val answer: String)

/** Builds the prompt the on-device model answers from. */
object AnswerPrompt {
    const val DECLINE_TOKEN = "NOT IN LIBRARY"
    private const val PRIOR_CHARS = 600
    private const val TEXT_CHARS = 1_200
    private const val QUOTE_CHARS = 1_500

    fun estimateTokens(prompt: String): Int = prompt.length / 4

    fun build(question: String, passages: List<Passage>, medical: Boolean, prior: PriorTurn?, hasQuote: Boolean): String =
        buildString {
            appendLine("You are Granger's research librarian. Answer the question using only the numbered passages from the user's library below.")
            appendLine("Rules:")
            appendLine("- Put the passage number in square brackets after every claim it supports, like [2]. Cite only the numbers listed.")
            appendLine("- If the passages don't answer the question, reply exactly: $DECLINE_TOKEN")
            appendLine("- Use the source details (title, author, date, collection) when they matter to the question.")
            appendLine("- When passages from different works bear on each other, say how, citing each.")
            appendLine("- Keep to what the passages say. Don't add facts from memory.")
            if (medical) appendLine("- This is a medical question. Quote the passages closely and add nothing beyond them.")
            if (prior != null) {
                appendLine()
                appendLine("Earlier in this conversation:")
                appendLine("Q: ${prior.question}")
                appendLine("A: ${cut(Citations.strip(prior.answer), PRIOR_CHARS)}")
            }
            appendLine()
            appendLine("Passages:")
            passages.forEachIndexed { i, p ->
                if (i > 0) appendLine()
                appendLine(header(i + 1, p))
                val limit = if (p.kind == SourceKind.SELECTION) QUOTE_CHARS else TEXT_CHARS
                appendLine(cut(p.text, limit))
            }
            appendLine()
            if (hasQuote) appendLine("The user selected passage [1] and is asking about it.")
            appendLine("Question: $question")
            append("Answer:")
        }

    private fun header(n: Int, p: Passage): String = buildString {
        append("[$n] ${p.sourceTitle}")
        if (p.meta.author.isNotBlank()) append(" — ${p.meta.author}")
        if (p.meta.date.isNotBlank()) append(" (${p.meta.date})")
        if (p.meta.collection.isNotBlank()) append(" · ${p.meta.collection}")
        if (p.heading.isNotBlank()) append(" · ${p.heading}")
    }

    /** Cuts to at most [max] characters at a word boundary and appends an ellipsis. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val space = head.indexOfLast { it.isWhitespace() }
        return (if (space > 0) head.substring(0, space) else head).trimEnd() + "…"
    }
}
