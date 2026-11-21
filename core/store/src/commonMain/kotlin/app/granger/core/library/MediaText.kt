package app.granger.core.library

import app.granger.core.text.decompose

/**
 * How media text is matched: folded, then split into words. Search, Research and Auto all match through this. Shared
 * since Plan I6: NFKD through the shared fold (`decompose`), so both phones fold alike.
 */
object MediaText {
    private val MARKS = Regex("\\p{M}+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** NFKD, combining marks dropped, lower-cased without regard to locale (Locale.ROOT). */
    fun fold(s: String): String = decompose(s, compat = true).replace(MARKS, "").lowercase()

    /** fold(s) split into [\p{L}\p{N}]+ words. */
    fun words(s: String): List<String> = WORD.findAll(fold(s)).map { it.value }.toList()
}
