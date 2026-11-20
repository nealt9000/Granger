package app.granger.core.research

/** The prompt for a best guess: the model answers from its own knowledge, with no passages and no citations. */
object GuessPrompt {
    private const val PRIOR_MAX_CHARS = 300

    fun build(question: String, priorQuestion: String?): String = buildString {
        append("You have no sources for this question, so answer from your own knowledge.\n")
        append("Answer in a few sentences. Do not cite or number anything.\n")
        append("If you don't know, or can't know, say so plainly instead of guessing.\n")
        if (priorQuestion != null) append("Previous question: ").append(priorQuestion.take(PRIOR_MAX_CHARS)).append('\n')
        append("Question: ").append(question)
    }
}
