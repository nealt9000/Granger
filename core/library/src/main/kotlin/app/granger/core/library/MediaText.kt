package app.granger.core.library

import java.text.Normalizer
import java.util.Locale

/** How media text is matched: folded, then split into words. Search, Research and Auto all match through this. */
object MediaText {
    private val MARKS = Regex("\\p{M}+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** NFKD, combining marks dropped, lowercase(Locale.ROOT). */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)

    /** fold(s) split into [\p{L}\p{N}]+ words. */
    fun words(s: String): List<String> = WORD.findAll(fold(s)).map { it.value }.toList()
}
