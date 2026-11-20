package app.granger.core.catalog

import app.granger.core.text.iso639Alpha2

/**
 * Language codes as the catalog writes them (Plan L3): BCP-47 primary subtags, comma-separated for a multilingual pack
 * ("en", "es", "en,es"). Older catalogs and installed packs' ZIM metadata say "eng" or "eng,spa"; [normalise] makes both
 * compare equal. Common to Android and iOS (the ISO table is the platform's, `iso639Alpha2`).
 */
object Languages {
    /** The JVM's legacy two-letter codes, in their current form. */
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * [code] as BCP-47 primary subtags: "eng" → "en", "eng,spa" → "en,es", "en-US" → "en"; order kept, duplicates
     * dropped. A code the table doesn't know ("mul") passes through, lower-cased.
     */
    fun normalise(code: String): String =
        code.split(',').map(::one).filter { it.isNotEmpty() }.distinct().joinToString(",")

    /** The primary codes of an entry's [entryLanguage], in order: "eng,spa" → [en, es]. */
    fun codes(entryLanguage: String): List<String> = normalise(entryLanguage).split(',').filter { it.isNotEmpty() }

    /** Whether an entry in [entryLanguage] is in [code]: a multilingual pack is in each of its languages. */
    fun has(entryLanguage: String, code: String): Boolean = normalise(code).let { it.isNotEmpty() && it in codes(entryLanguage) }

    private fun one(code: String): String {
        val primary = code.trim().substringBefore('-').substringBefore('_').lowercase()
        return if (primary.length == 3) iso639Alpha2(primary)?.let { LEGACY[it] ?: it } ?: primary else LEGACY[primary] ?: primary
    }
}
