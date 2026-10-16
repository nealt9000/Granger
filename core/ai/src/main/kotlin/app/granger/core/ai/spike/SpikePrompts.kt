package app.granger.core.ai.spike

object SpikePrompts {
    val QUESTION =
        "How long do I need to boil water to make it safe to drink, and what changes at high altitude?"

    private val PARAGRAPH =
        "Boiling is the surest method to kill disease-causing organisms, including viruses, bacteria, " +
            "and parasites. If the water is cloudy, let it settle and filter it through a clean cloth, " +
            "paper towel, or coffee filter before boiling. Bring the water to a rolling boil for at least " +
            "one minute. At elevations above 6,500 feet (about 2,000 meters), boil water for three minutes. " +
            "Let the boiled water cool naturally and store it in clean, sanitized containers with tight " +
            "covers. To improve the flat taste of boiled water, add one pinch of salt to each quart or liter " +
            "of water, or pour the water from one clean container to another several times."

    fun passages(n: Int): List<String> =
        (1..n).map { i -> "[$i] Water treatment, section $i. $PARAGRAPH $PARAGRAPH" }

    val REWRITE =
        "Rewrite the user's question as search keywords for an offline encyclopedia. " +
            "Reply only with JSON: {\"searchTerms\": [\"...\"], \"medical\": true or false}.\n" +
            "Question: $QUESTION"

    val ANSWER = buildString {
        appendLine(
            "Answer the question using only the numbered passages. Cite every claim like [1]. " +
                "If the passages do not contain the answer, reply exactly: NOT_IN_LIBRARY.",
        )
        appendLine()
        passages(4).forEach { appendLine(it); appendLine() }
        append("Question: $QUESTION")
    }
}
