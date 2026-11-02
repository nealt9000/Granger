package app.granger.core.research

/**
 * A medical answer in the library's own words (Neal, 2026-11-02: Ask Preview shows medical questions as the banner and
 * quoted passages, without generated prose). The model's reply is written out of sight and used only to choose: each
 * passage it cited gives the sentence its claims drew on ([CitedQuote.pick]), quoted with its citation, one per line.
 */
object MedicalQuotes {
    private val line = Regex("^“.+” \\[\\d+]$")

    /** The quoted answer for [reply]'s [cited] passages (1-based), in citation order; blank when none gives a sentence. */
    fun build(reply: String, passages: List<Passage>, cited: Set<Int>, languages: List<ResearchLanguage> = Keywords.ENGLISH): String {
        val seen = HashSet<String>()
        return cited.sorted().mapNotNull { n ->
            val passage = passages.getOrNull(n - 1) ?: return@mapNotNull null
            val sentence = CitedQuote.pick(reply, n, passage.text, languages, passage.language)?.sentence?.trim()
                ?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return@mapNotNull null
            "“$sentence” [$n]"
        }.joinToString("\n\n")
    }

    /** Whether a stored answer is quotes alone, as [build] writes them (the screen labels it as quoted). */
    fun isQuoted(answer: String): Boolean {
        val lines = answer.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.all { line.matches(it.trim()) }
    }
}
