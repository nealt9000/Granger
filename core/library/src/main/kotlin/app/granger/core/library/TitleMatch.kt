package app.granger.core.library

/**
 * How a title matches a query in Search's Titles (Plan SQ Task 4.3), on folded words ([SearchText.words]) so case,
 * accents and punctuation don't count. Pure.
 */
object TitleMatch {
    /** [text]'s folded words joined by single spaces: "Guillain–Barré syndrome" and "guillain barre syndrome" alike. */
    fun key(text: String): String = SearchText.words(text).joinToString(" ")

    /** [title] against the folded query [q]: exact, starts with it, or neither (null). */
    fun rank(title: String, q: String): BookTitleSearch.Rank? {
        val t = key(title)
        return when {
            t.isEmpty() || q.isEmpty() -> null
            t == q -> BookTitleSearch.Rank.EXACT
            t.startsWith(q) -> BookTitleSearch.Rank.STARTS_WITH
            else -> null
        }
    }

    /**
     * Edits a typo may cost: none under five letters (too many short words are a slip apart), one up to eight, two
     * beyond. Counted on the folded query.
     */
    fun budget(q: String): Int = when {
        q.length < 5 -> 0
        q.length <= 8 -> 1
        else -> 2
    }

    /**
     * Restricted Damerau–Levenshtein distance (insert, delete, substitute, swap two neighbours), stopping early once it
     * passes [max]: then any value above [max] is returned.
     */
    fun distance(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, prev2[j - 2] + 1)
                cur[j] = d
                rowMin = minOf(rowMin, d)
            }
            if (rowMin > max) return max + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /**
     * The title searches that can reach a query with one slip in it: for each word of four letters or more, the query
     * with that word cut to its first half and put last (a title index takes the last word as a prefix and the others
     * whole), so "Atomic orbtal" asks for "atomic orb" and "Steatorreha" for "steat". A slip in the first half of a
     * word is left to the other words (each long word is also left out, below); a one-word query with one there isn't found.
     */
    fun probes(query: String): List<String> {
        val words = SearchText.words(query)
        val long = words.indices.filter { words[it].length >= 4 }
        val cut = long.map { i ->
            val head = words[i].take(maxOf(2, words[i].length / 2))
            (words.filterIndexed { k, _ -> k != i } + head).joinToString(" ")
        }
        // With two words or more, also each long word left out, for a slip early in it ("Kerri Hiugchi" asks for "kerri").
        val dropped = if (words.size < 2) emptyList() else long.map { i -> words.filterIndexed { k, _ -> k != i }.joinToString(" ") }
        return (cut + dropped).filter { it.isNotBlank() }.distinct()
    }

    private val THREE = mapOf("en" to "eng", "es" to "spa")

    /**
     * Whether a pack whose ZIM Language is [packLanguage] ("eng", "spa", "eng,spa", or blank) is in the language [tag]
     * ("en", "es"). A blank or multilingual ("mul") pack counts for every language.
     */
    fun inLanguage(packLanguage: String, tag: String): Boolean {
        val codes = packLanguage.lowercase().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        return codes.isEmpty() || "mul" in codes || tag in codes || THREE[tag] in codes
    }

    /** A pack name's family, the packs sharing article paths: its first two parts ("wikipedia_en_history" → "wikipedia_en"). */
    fun family(packName: String): String = packName.split('_').take(2).joinToString("_")

    private val TRANSLATION = Regex("/([a-z]{2,3}(?:-[a-z]{2,4})?)$")
    private val LANGUAGES = setOf(
        "ar", "bn", "cs", "de", "el", "es", "fa", "fr", "he", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt", "pt-br",
        "ro", "ru", "sv", "sw", "th", "tr", "uk", "ur", "vi", "zh", "zh-hans", "zh-hant",
    )

    /**
     * A translated copy of a page, as WikEM keeps them ("Guillain-Barre syndrome/de", "…/pt-br"): a language code ending
     * both title and path, so "AC/DC" is left alone.
     */
    fun isTranslation(path: String, title: String): Boolean {
        val code = TRANSLATION.find(title.lowercase())?.groupValues?.get(1) ?: return false
        return code in LANGUAGES && path.lowercase().endsWith("/$code")
    }

    /**
     * Where a pack's hits sit among others of the same rank: lower first. Dictionaries go after encyclopedias and
     * guides ("Maputo" the city before "Maputo" the word), and translated copies after everything.
     */
    /**
     * Whether a pack is left out of title suggestions for [query]: a dictionary while the last word is one or two letters
     * (Plan SQ, the phone: libkiwix expands a short last word over English Wiktionary's millions of titles, 1–3 s a call
     * on the emulator, mostly for no hits, on keystrokes of "paradise r", "eduardo de" or "sea walls of c").
     */
    fun skipsSuggest(packName: String, query: String): Boolean =
        packName.startsWith("wiktionary") && query.trim().substringAfterLast(' ').length <= 2

    fun prior(packName: String, path: String, title: String): Int = when {
        isTranslation(path, title) -> 2
        packName.startsWith("wiktionary") -> 1
        else -> 0
    }
}
