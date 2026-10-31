package app.granger.core.research

import java.text.Normalizer
import java.util.Locale

/**
 * The languages Research knows (languages spec §6.2). English is today's lists unchanged; Spanish is new.
 *
 * [of] says which a question is in, cheaply, from function words only those languages use. English stays in the set
 * unless the question is clearly Spanish, so an English question keeps every English list ("Los Angeles population"
 * has one Spanish word and stays English).
 */
enum class ResearchLanguage(val tag: String) {
    EN("en"),
    ES("es");

    companion object {
        /** English function words that aren't Spanish words ("me", "no", "a", "son" and "has" are both, so they don't count). */
        private val ENGLISH = setOf(
            "the", "is", "are", "was", "were", "of", "to", "in", "on", "at", "do", "does", "did", "an", "it", "be", "by",
            "or", "as", "if", "so", "we", "my", "and", "for", "you", "what", "who", "whom", "whose", "when", "where",
            "which", "why", "how", "this", "that", "these", "those", "with", "from", "into", "than", "then", "they",
            "their", "there", "about", "been", "being", "will", "would", "could", "should", "can", "have", "had", "her",
            "his", "she", "him", "our", "your", "not", "but", "any", "all", "some", "much", "many", "after", "before",
        )
        /** Spanish function words that aren't English words, written without accents (the question is folded first). */
        private val SPANISH = setOf(
            "el", "la", "los", "las", "del", "al", "que", "es", "por", "para", "con", "una", "uno", "unos", "unas",
            "como", "cual", "cuales", "cuando", "donde", "quien", "quienes", "cuanto", "cuanta", "cuantos", "cuantas",
            "se", "su", "sus", "mi", "mis", "lo", "le", "les", "pero", "porque", "mas", "muy", "fue", "era", "eran",
            "esta", "estan", "estoy", "hay", "tiene", "tienen", "tengo", "entre", "sobre", "desde", "hasta", "sin",
            "este", "estos", "estas", "ese", "esa", "esos", "esas", "yo", "tu", "te", "nos", "y", "o", "ya", "si",
            "puedo", "puede", "debo", "hago", "hace", "sirve", "significa", "cuantos", "pasa", "dia", "dias",
        )
        private val WORD = Regex("[\\p{L}\\p{N}]+")
        private val MARKS = Regex("\\p{M}+")
        private val SPANISH_MARKS = Regex("[¿¡ñÑ]")

        /** Lower case, accents off, ’ as ': how every Spanish list here is written and matched. */
        fun fold(text: String): String =
            MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).replace('’', '\'')

        /**
         * The languages [question] is in, the main one first. Signs are function words only one language uses (¿ ¡ ñ
         * count as two Spanish ones). Spanish signs and no English ones: Spanish alone. Both: Spanish needs two to
         * count, and the one with more leads. Neither: both, English first.
         */
        fun of(question: String): List<ResearchLanguage> {
            val tokens = WORD.findAll(question).toList()
            // A capitalised word before another is part of a name ("Los Angeles", "La Paz", "El Paso"), not Spanish.
            val words = tokens.filterIndexed { i, m ->
                !(m.value.first().isUpperCase() && tokens.getOrNull(i + 1)?.value?.first()?.isUpperCase() == true)
            }.map { fold(it.value) }
            val en = words.count { it in ENGLISH }
            val es = words.count { it in SPANISH } + if (SPANISH_MARKS.containsMatchIn(question)) 2 else 0
            return when {
                es >= 1 && en == 0 -> listOf(ES)
                es >= 2 && es > en -> listOf(ES, EN)
                es >= 2 -> listOf(EN, ES)
                en >= 1 -> listOf(EN)
                else -> listOf(EN, ES)
            }
        }

        /** The main language of [question]: the first of [of]. */
        fun main(question: String): ResearchLanguage = of(question).first()
    }
}
