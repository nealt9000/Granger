package app.granger.core.speech

/**
 * Respellings applied to text before a Piper voice speaks it, whole words only, case preserved. The system engine
 * gets the original text: it handles names better on its own.
 *
 * A respelling only helps when it changes the phonemes espeak-ng (Piper's phonemizer) produces; check with
 * `espeak-ng -v en-us -q --ipa "<word>"`. "Rosetta" and "Rozetta" both give ɹoʊzˈɛɾə, and every respelling Neal
 * listened to (2026-10-22) still ended in the same hard "ah", so the "rossetter" heard in V0 is the ljspeech model's
 * rendering of a final schwa, not the phonemes. The built-in table is empty until a list of mispronounced names
 * exists; the mechanism stays for it. Years, ranges and units are [SpokenText]'s job (Plan V2), which runs first.
 */
object Pronunciations {
    val builtIn: Map<String, String> = emptyMap()

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
