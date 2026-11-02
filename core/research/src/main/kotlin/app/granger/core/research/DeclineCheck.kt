package app.granger.core.research

/**
 * Deterministic declines (Plan R2 Task 2): questions no library could answer, and answers that can't be about the
 * question. E2B answers from off-topic passages (R5: 13 of 76 unanswerables answered), so these catch what the model
 * won't decline on its own. English phrasings only; a question that isn't English keeps the model's own judgment.
 *
 * Kept narrow on purpose: "my" alone is everywhere in real questions ("how do I fix my bike chain", "change my car's
 * oil", "find my IP address"); "nearby" asks about stars as often as shops; a street name may be Baker Street or Abbey
 * Road; and "when will" asks about the sky as often as the news ("when will Halley's comet return").
 */
object DeclineCheck {
    /** Somewhere near the asker: a library holds no shop's hours or the street's parking. */
    private val LOCAL = listOf(
        "\\bnear me\\b", "\\baround here\\b", "\\bdown the (street|road|block)\\b",
        "\\bwhat('s| is) the wi-?fi password\\b",
        "\\b(my|our) (street|town|city|neighbou?rhood|county)\\b",
        "\\b(what time|when) does (the|my) [^?]* (open|close)\\b",
        "\\b(is|are) (the|my) [^?]* (open|closed) (late|now|today|tonight|tomorrow|on (sun|mon|tues|wednes|thurs|fri|satur)days?)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** The asker's own records and people: only they know them. */
    private val PERSONAL = Regex(
        "\\bmy (\\w+'s |\\w+ ){0,2}(password|passcode|(bank|checking|savings) account|account balance|phone number|" +
            "birthday|package|delivery|flight|appointment|reservation|representative|senator|congress(wo)?man|mayor|" +
            "license plate|insurance|test score|salary|paycheck|gate code|shoe size|search history)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Live or upcoming, not in any snapshot: "right now", "tonight", "this season", "last night's game". */
    private val LIVE = Regex(
        "\\b(right now|tonight|last night|this (season|weekend|week|morning|afternoon|evening|saturday|sunday)|" +
            "next (week|weekend|season))\\b",
        RegexOption.IGNORE_CASE,
    )

    private val YEAR = Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b")

    /**
     * True when no library could answer [question] offline: it's about somewhere near the asker, the asker's own
     * records, or something live. Checked before search, so it declines at once.
     */
    fun unknowable(question: String, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Boolean {
        if (ResearchLanguage.EN !in languages) return false
        val q = question.replace('’', '\'')
        return LOCAL.any { it.containsMatchIn(q) } || PERSONAL.containsMatchIn(q) || LIVE.containsMatchIn(q)
    }

    /**
     * True when the answer can't be about [question]: the question names a year that none of the [cited] passages has
     * ("the 2026 World Series" answered from the 2016 one). Not for questions that work with dates ([Entities.asked]),
     * whose year may be the result rather than a fact in the text.
     */
    fun offTopic(question: String, cited: List<Passage>, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Boolean {
        if (cited.isEmpty() || Entities.asked(question, languages)) return false
        val years = YEAR.findAll(question).map { it.value }.toSet()
        if (years.isEmpty()) return false
        val text = cited.joinToString(" ") { "${it.sourceTitle} ${it.heading} ${it.text}" }
        return years.none { it in text }
    }
}
