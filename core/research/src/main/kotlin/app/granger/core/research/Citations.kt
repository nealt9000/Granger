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

    /**
     * Spanish declines, in case the model answers in Spanish: "los pasajes no …", "no se menciona", "no se especifica",
     * "no se indica", "no hay información", "no encuentro", "no pude encontrar".
     */
    private val lacksAnswerEs = Regex(
        "(los |las |estos |estas |la |esta )?(pasajes?|fuentes?|textos?|biblioteca)( proporcionad[oa]s| dad[oa]s)? no\\b|" +
            "no se (menciona|especifica|indica|dice|encuentra|proporciona|incluye|detalla)n?\\b|" +
            "no hay (ninguna )?informaci[oó]n|" +
            "no (encuentro|encontr[eé]|pude encontrar|puedo encontrar|logr[eé] encontrar)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val goesOnToAnswerEs = Regex("\\b(pero|sin embargo|aunque|mientras que)\\b", RegexOption.IGNORE_CASE)
    /** Sr., Sra., Dr., Dra., Sto., Sta., Ud., Uds. */
    private val abbreviationEs = Regex("(?:^|\\s)(?:Sr|Sra|Dr|Dra|Sto|Sta|Ud|Uds)$")
    /** The "a." of "a. C." or "a. de C." (and "d. C."): the era carries on. */
    private val eraStartEs = Regex("(?:^|\\s)[ad]$")
    private val eraRestEs = Regex("^\\s?(?:de\\s?)?C\\.")
    /** The "C." that ends an era: a sentence end unless the text carries on in lower case ("44 a. C. y su hijo"). */
    private val eraEndEs = Regex("(?:^|\\s)[ad]\\.\\s?(?:de\\s?)?C$")
    private val lowerNext = Regex("^\\s+\\p{Ll}")

    /**
     * Up to the first sentence end: not after an honorific-style abbreviation (St.) or a dotted one (e.g., U.S.); with
     * [spanish], nor after a Spanish one (Sra., a. C.).
     */
    internal fun firstSentence(answer: String, spanish: Boolean = false): String {
        val text = answer.trimStart().replace(leadingGroup, "")
        for (m in sentenceEnd.findAll(text)) {
            if (m.value != "\n") {
                val before = text.substring(0, m.range.first)
                if (abbreviation.containsMatchIn(before)) continue
                if (spanish && abbreviationEs.containsMatchIn(before)) continue
                val after = text.substring(m.range.last + 1)
                if (spanish && eraStartEs.containsMatchIn(before) && eraRestEs.containsMatchIn(after.trimStart())) continue
                if (spanish && eraEndEs.containsMatchIn(before) && lowerNext.containsMatchIn(after)) continue
            }
            return text.substring(0, m.range.first)
        }
        return text
    }

    /**
     * English always, since the prompts are English and the model may answer a Spanish question in English; Spanish
     * too when the question may be Spanish.
     */
    private fun declinesInProse(answer: String, spanish: Boolean): Boolean {
        val sentence = firstSentence(answer, spanish)
        val lacks = lacksAnswer.containsMatchIn(sentence) || (spanish && lacksAnswerEs.containsMatchIn(sentence))
        val goesOn = goesOnToAnswer.containsMatchIn(sentence) || (spanish && goesOnToAnswerEs.containsMatchIn(sentence))
        return lacks && !goesOn
    }

    /** [languages] are the question's ([ResearchLanguage.of]); English alone by default. */
    fun check(answer: String, passageCount: Int, languages: List<ResearchLanguage> = Keywords.ENGLISH): CitationCheck {
        // Anywhere, not just first: a model that cites a line and then declines has still declined.
        if (AnswerPrompt.DECLINE_TOKEN in answer) return CitationCheck.ModelDeclined
        if (declinesInProse(answer, ResearchLanguage.ES in languages)) return CitationCheck.ModelDeclined
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
