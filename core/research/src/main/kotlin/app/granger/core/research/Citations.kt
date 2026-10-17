package app.granger.core.research

sealed interface CitationCheck {
    data class Valid(val cited: Set<Int>) : CitationCheck
    data object Invalid : CitationCheck
    data object ModelDeclined : CitationCheck
}

/** Checks that an answer cites only the passages it was given. */
object Citations {
    private val group = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")

    fun check(answer: String, passageCount: Int): CitationCheck {
        if (answer.trim().startsWith(AnswerPrompt.DECLINE_TOKEN)) return CitationCheck.ModelDeclined
        // A digit run too big for Int is bad model output, not a crash.
        val parsed = group.findAll(answer)
            .flatMap { it.groupValues[1].split(',').map { n -> n.trim().toIntOrNull() } }
            .toList()
        if (parsed.any { it == null }) return CitationCheck.Invalid
        val numbers = parsed.filterNotNull().toSet()
        if (numbers.isEmpty() || numbers.any { it !in 1..passageCount }) return CitationCheck.Invalid
        return CitationCheck.Valid(numbers)
    }

    fun strip(answer: String): String = answer.replace(Regex("\\s*" + group.pattern), "").trim()
}
