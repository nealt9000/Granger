package app.granger.search

import app.granger.UiText
import app.granger.core.library.SearchText

/**
 * Search's Define card (Plan SQ Task 5.4), shared with the iPhone since Plan I7 Task 7b: which queries look a word up,
 * and the card's line from the entry (`DictionaryEntry`'s "Heading: text" lines).
 */
object SearchDefine {
    /** One word of at least two letters: a query the Define card looks up. */
    fun isOneWord(q: String): Boolean =
        q.length >= 2 && q.none { it.isWhitespace() } && q.any { it.isLetter() } && q.all { it.isLetter() || it == '-' || it == '\'' || it == '’' }

    /** The card's text: the senses, the etymology left out when there is anything else, on one line. */
    fun text(entry: String): String {
        val lines = entry.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val senses = lines.filterNot { ETYMOLOGY.containsMatchIn(SearchText.fold(it.substringBefore(':'))) }
        return senses.ifEmpty { lines }.joinToString(" ")
    }

    /** The card's label, "Define" (Definición: the e-book menu's Define is another string, Definir). */
    val label: UiText get() = UiText.Res("search_define")

    /** The card's foot: "Dictionary entry from <the Wiktionary pack's title>", Wiktionary when it has none. */
    fun source(packTitle: String): UiText = UiText.Res("search_define_source", packTitle.ifBlank { "Wiktionary" })

    private val ETYMOLOGY = Regex("^(etymolog|etimolog)")
}
