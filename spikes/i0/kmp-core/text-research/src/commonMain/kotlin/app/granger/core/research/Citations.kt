package app.granger.core.research

sealed interface CitationCheck {
    data class Valid(val cited: Set<Int>) : CitationCheck
    data object Invalid : CitationCheck
    data object ModelDeclined : CitationCheck
}

/** Checks that an answer cites only the passages it was given. */
object Citations {
    private val group = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")

    /**
     * A first sentence saying the passages lack the answer: "the passages (or library, sources, text) do not ...",
     * "... contain no ...", "there is no information", "no information was provided", "I cannot find",
     * "X is not specified / mentioned / provided (in the library)".
     */
    private val lacksAnswer = Regex(
        "(the |these |provided |given )*(passages?|library|sources?|texts?) (do|does|did) not|" +
            "(the |these |provided |given )*(passages?|library|sources?|texts?) (don['’]t|doesn['’]t)|" +
            "(the |these |provided |given )*(passages?|library|sources?|texts?) (contain|provide|include|mention|say|state)s? no|" +
            "there is no information|no information (is|was) (given|provided|available)|" +
            "i (cannot|can['’]t|could not|couldn['’]t) find|" +
            "(is|are|was|were) not (mentioned|stated|specified|provided|given|found|covered|included|listed)\\b|" +
            "not (mentioned|stated|found|covered) in the passages",
        RegexOption.IGNORE_CASE,
    )
    private val goesOnToAnswer = Regex("\\b(but|however|although|though|while|yet)\\b|[;:]\\s*\\S", RegexOption.IGNORE_CASE)
    private val leadingGroup = Regex("^\\s*" + group.pattern + "\\s*")
    private val sentenceEnd = Regex("[.!?](?=\\s)|\\n")
    private val abbreviation = Regex("(?:^|\\s)(?:(?:Mr|Mrs|Ms|Dr|St|vs|No|Mt|Jr|Sr)|\\p{L}(?:\\.\\p{L})+)$")

    /** Up to the first sentence end: not after an honorific-style abbreviation (St.) or a dotted one (e.g., U.S.). */
    internal fun firstSentence(answer: String): String {
        val text = answer.trimStart().replace(leadingGroup, "")
        for (m in sentenceEnd.findAll(text)) {
            if (m.value != "\n") {
                val before = text.substring(0, m.range.first)
                if (abbreviation.containsMatchIn(before)) continue
            }
            return text.substring(0, m.range.first)
        }
        return text
    }

    private fun declinesInProse(answer: String): Boolean {
        val sentence = firstSentence(answer)
        return lacksAnswer.containsMatchIn(sentence) && !goesOnToAnswer.containsMatchIn(sentence)
    }

    fun check(answer: String, passageCount: Int): CitationCheck {
        // Anywhere, not just first: a model that cites a line and then declines has still declined.
        if (AnswerPrompt.DECLINE_TOKEN in answer) return CitationCheck.ModelDeclined
        if (declinesInProse(answer)) return CitationCheck.ModelDeclined
        // A digit run too big for Int is bad model output, not a crash.
        val parsed = group.findAll(answer)
            .flatMap { it.groupValues[1].split(',').map { n -> n.trim().toIntOrNull() } }
            .toList()
        if (parsed.any { it == null }) return CitationCheck.Invalid
        val numbers = parsed.filterNotNull().toSet()
        if (numbers.isEmpty() || numbers.any { it !in 1..passageCount }) return CitationCheck.Invalid
        return CitationCheck.Valid(numbers)
    }

    fun strip(answer: String): String = answer.replace(Regex("[ \\t]*" + group.pattern), "").trim()
}
