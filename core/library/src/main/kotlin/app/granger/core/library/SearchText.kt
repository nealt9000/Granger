package app.granger.core.library

import java.text.Normalizer

/** Text compared the way a search compares it: case and accents don't count. */
object SearchText {
    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** [text] lower-cased with its accents dropped: "Ève" and "eve" fold alike. */
    fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** [query]'s words, folded. */
    fun words(query: String): List<String> = fold(query).split(NOT_WORD).filter { it.isNotEmpty() }
}
