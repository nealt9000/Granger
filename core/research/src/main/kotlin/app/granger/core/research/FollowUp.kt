package app.granger.core.research

import java.util.Locale

/**
 * Whether a question in a chat leans on the one before it. The app sends every question after the first with its prior
 * one; a question that stands alone must be searched alone, or the prior topic pulls the search its way (Plan R5 Task 7:
 * ten of Neal's questions in one chat declined that way, nine answered asked on their own).
 */
object FollowUp {
    /** Words that point back at something named earlier. Not "that" or "there": "is there a book", "the one that". */
    private val BACK = setOf("he", "she", "it", "they", "him", "her", "his", "hers", "its", "their", "theirs", "them", "this", "these", "those")
    /** Openers that carry on from the last answer: "And at altitude?", "What about Mars?". */
    private val OPENERS = listOf(listOf("and"), listOf("or"), listOf("but"), listOf("what", "about"), listOf("how", "about"))
    private val WORD = Regex("[\\p{L}']+")

    /** A word of [BACK] as a whole word ("it's" and "they're" count), or one of the [OPENERS] first. */
    fun refersBack(question: String): Boolean {
        val words = WORD.findAll(question.lowercase(Locale.ROOT)).map { it.value }.toList()
        return words.any { it.substringBefore('\'') in BACK } || OPENERS.any { words.take(it.size) == it }
    }
}
