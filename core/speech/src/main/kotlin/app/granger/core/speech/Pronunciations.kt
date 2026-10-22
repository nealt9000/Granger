package app.granger.core.speech

/**
 * Respellings applied to text before a Piper voice speaks it, whole words only, case preserved. The system engine
 * gets the original text: it handles names better on its own.
 *
 * A respelling only helps when it changes the phonemes espeak-ng (Piper's phonemizer) produces; check with
 * `espeak-ng -v en-us -q --ipa "<word>"`. "Rosetta" and "Rozetta" both give ɹoʊzˈɛɾə, so the "rossetter" heard in V0
 * and the V1 walkthrough is the ljspeech model's rendering of the flapped t and final schwa, not the phonemes; the
 * entry stays until a listening test picks a respelling that sounds right.
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
