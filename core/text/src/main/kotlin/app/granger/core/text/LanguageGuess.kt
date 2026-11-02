package app.granger.core.text

import java.util.Locale

/**
 * A text's language for its EPUB's `dc:language` (Plan L3 Task 9): the declared one when it names a language, else a
 * guess between English and Spanish by stopwords. Pure JVM.
 */
object LanguageGuess {
    /** How many words of the text the guess reads. */
    const val WORDS = 2_000

    /** English's commonest function words. */
    val ENGLISH = setOf(
        "the", "of", "and", "to", "a", "in", "is", "it", "that", "was", "he", "for", "on", "with", "as", "his", "they", "at",
        "be", "this", "from", "have", "or", "by", "but", "not", "what", "all", "were", "we", "when", "there", "she", "which",
        "you", "had", "her", "would", "their", "been", "has", "him", "my", "are", "an", "will", "if", "no", "so", "into",
    )

    /** Spanish's commonest function words. "a" and "no" are both languages': they count for neither. */
    val SPANISH = setOf(
        "de", "la", "que", "el", "en", "y", "los", "se", "del", "las", "un", "por", "con", "una", "su", "para", "es", "al",
        "lo", "como", "más", "pero", "sus", "le", "ya", "o", "fue", "este", "ha", "sí", "porque", "esta", "son", "entre",
        "cuando", "muy", "sin", "sobre", "también", "me", "hasta", "hay", "donde", "quien", "desde", "todo", "nos", "durante",
        "todos", "uno", "les", "ni", "contra", "otros", "ese", "eso", "había", "ante", "ellos", "e", "esto", "mí", "antes",
        "algunos", "qué", "unos", "yo", "otro", "otras", "otra", "él", "tanto", "esa", "estos", "mucho", "quienes", "nada",
        "muchos", "cual", "poco", "ella", "estar", "estas", "algunas", "algo", "nosotros", "mi", "mis", "tú", "te", "ti", "tu",
    )

    private val WORD = Regex("[\\p{L}']+")

    /** [declared] as a BCP-47 primary tag when it names a language ("eng", "Spanish", "es-MX"), else [guess] of [text]. */
    fun tag(declared: String?, text: String): String = declared(declared) ?: guess(text)

    /** "en" or "es" for a declared ISO 639-1/2/3 code or an English name; null for a blank or unknown one. */
    fun declared(language: String?): String? {
        val first = language?.split(',', ';')?.firstOrNull()?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        if (first.isNullOrEmpty()) return null
        NAMES[first]?.let { return it }
        if (first.length == 2 && first in Locale.getISOLanguages()) return first
        if (first.length == 3) return Locale.getISOLanguages().firstOrNull { Locale(it).isO3Language == first }
        return null
    }

    private val NAMES = mapOf("english" to "en", "spanish" to "es", "español" to "es", "castellano" to "es", "inglés" to "en")

    /**
     * "es" when the first [WORDS] words hold more of Spanish's stopwords than of English's, else "en" (an empty or
     * other-language text stays English, as before).
     */
    fun guess(text: String): String {
        var en = 0
        var es = 0
        for ((n, match) in WORD.findAll(text).withIndex()) {
            if (n >= WORDS) break
            val word = match.value.lowercase(Locale.ROOT)
            if (word in ENGLISH && word !in SPANISH) en++
            if (word in SPANISH && word !in ENGLISH) es++
        }
        return if (es > en) "es" else "en"
    }
}
