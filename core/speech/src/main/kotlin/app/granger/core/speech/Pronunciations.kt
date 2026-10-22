package app.granger.core.speech

/**
 * Respellings applied to text before a Piper voice speaks it. Piper's phonemizer (espeak-ng) says "rossetter" for
 * Rosetta (V0 listening, 2026-10-21); a respelling fixes the word everywhere without touching the engine. Whole words
 * only, case preserved. The system engine gets the original text: it handles names better on its own.
 */
object Pronunciations {
    val builtIn: Map<String, String> = mapOf(
        "Rosetta" to "Rozetta",
    )

    fun apply(text: String, table: Map<String, String> = builtIn): String {
        if (table.isEmpty()) return text
        val pattern = patterns.getOrPut(table) {
            Regex("\\b(" + table.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
        }
        val lower = table.mapKeys { it.key.lowercase() }
        return pattern.replace(text) { m ->
            val found = m.value
            val replacement = lower[found.lowercase()] ?: found
            when {
                found.length > 1 && found.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
                found.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
                else -> replacement.lowercase()
            }
        }
    }

    private val patterns = HashMap<Map<String, String>, Regex>()
}
