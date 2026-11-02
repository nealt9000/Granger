package app.granger.core.speech

/**
 * Which recognizer language the mic listens in (Plan A1): the app's language decides. English uses the variant
 * chosen in Settings (US by default, UK as a download). Spanish uses the best installed Spanish (es-ES, then es-US,
 * then any), else the best downloadable one. Any other language has no mic yet.
 */
object RecognizerTags {
    const val ENGLISH_US = "en-US"
    const val ENGLISH_UK = "en-GB"
    private val SPANISH_ORDER = listOf("es-ES", "es-US")

    /**
     * The tag for an app whose language is [appLanguage] (ISO 639, e.g. `en`, `es`), or null when there's no mic
     * for it. [englishTag] is Settings' choice. The result may only be downloadable: check
     * [RecognizerLanguages.availability].
     */
    fun forApp(appLanguage: String, englishTag: String, languages: RecognizerLanguages?): String? {
        languages ?: return null
        return when (appLanguage.lowercase()) {
            "en" -> pick(listOf(englishTag), "en", languages, preferWanted = true)
            "es" -> pick(SPANISH_ORDER, "es", languages, preferWanted = false)
            else -> null
        }
    }

    /**
     * With [preferWanted], the first of [wanted] that exists at all (installed or downloadable) wins, so a chosen
     * English variant is offered for download rather than swapped. Without it, anything installed beats a download.
     */
    private fun pick(wanted: List<String>, language: String, l: RecognizerLanguages, preferWanted: Boolean): String? {
        fun inList(list: List<String>, tag: String) = list.firstOrNull { it.equals(tag, ignoreCase = true) }
        fun ofLanguage(list: List<String>) = list.firstOrNull { it.substringBefore('-').equals(language, ignoreCase = true) }
        if (preferWanted) wanted.firstNotNullOfOrNull { inList(l.installed, it) ?: inList(l.supported, it) }?.let { return it }
        return wanted.firstNotNullOfOrNull { inList(l.installed, it) }
            ?: ofLanguage(l.installed)
            ?: wanted.firstNotNullOfOrNull { inList(l.supported, it) }
            ?: ofLanguage(l.supported)
    }
}
