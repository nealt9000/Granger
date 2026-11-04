package app.granger.core.library

import app.granger.core.research.HtmlPassage
import app.granger.core.research.HtmlPassages
import app.granger.core.research.ResearchLanguage
import app.granger.core.zim.ZimArchive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * A word's entry in a Wiktionary ZIM, as one passage's text: the first language section's etymology and senses,
 * without the translations, pronunciation, references and word lists. An inflected form's entry ("madrugué": "Primera
 * persona del singular … de madrugar") leads to its lemma's, with the form's line kept first.
 *
 * Both Wiktionaries head each language with an h2 ("English", "Italian"; "Español", "Gallego"), so the entry is the
 * page up to its second h2. English senses carry their quotations and examples as lists nested in the sense; they are
 * dropped, or "betimes" is all quotation and its meaning never fits (l4-words-en-2).
 */
internal object DictionaryEntry {
    const val PACK_PREFIX = "wiktionary_"
    private const val MAX_CHARS = 1_100
    private const val SEARCHED = 5
    /** Less room than this left, and the next part isn't started. */
    private const val MIN_PART = 80

    /** Sections that are lists or apparatus, not what the word means or where it comes from. */
    private val SKIP = setOf(
        "traducciones", "pronunciacion", "pronunciacion y escritura", "referencias y notas", "vease tambien",
        "locuciones", "refranes", "informacion adicional", "relacionados", "derivados", "conjugacion", "flexion",
        "translations", "pronunciation", "references", "see also", "derived terms", "related terms", "anagrams",
        "descendants", "conjugation", "inflection", "declension", "further reading", "alternative forms", "usage notes",
        "hyponyms", "hypernyms", "synonyms", "antonyms", "coordinate terms", "quotations", "compounds",
    )
    /** A form's sense ends by naming its lemma: "… de madrugar.", "Forma del plural de bebé.", "plural of cat". */
    private val FORM_OF = Regex("\\b(de|of)\\s+([\\p{L}'-]+)\\s*\\.?\\s*$")
    private val FORM_HEADING = Regex("^(forma|form)\\b", RegexOption.IGNORE_CASE)
    private val FORM_TEXT = Regex("\\b(plural|past tense|past participle|present participle|third-person singular|comparative|superlative|inflection) of\\b", RegexOption.IGNORE_CASE)

    data class Found(val path: String, val title: String, val text: String)

    fun find(archive: ZimArchive, word: String): Found? {
        val exact = readEntry(archive, word) ?: readEntry(archive, word.lowercase())
        if (exact != null) {
            val lemma = lemmaOf(exact.second)
            if (lemma == null || lemma.equals(word, ignoreCase = true)) return summary(exact.first, word, exact.second, prefix = null)
            val form = "${word}: " + exact.second.first().text
            val lemmaEntry = readEntry(archive, lemma) ?: return summary(exact.first, word, exact.second, prefix = null)
            return summary(lemmaEntry.first, lemma, lemmaEntry.second, prefix = form)
        }
        // No entry under the word as typed: the search's best single-word title that is plainly the same word with
        // another ending ("apapachó" when only "apapachar" is there; the index stems).
        val hit = archive.searchWithoutSnippets(word, SEARCHED).firstOrNull { sameStem(it.title, word) } ?: return null
        val entry = readEntry(archive, hit.path) ?: return null
        return summary(entry.first, hit.title, entry.second, prefix = null)
    }

    private fun readEntry(archive: ZimArchive, path: String): Pair<String, List<HtmlPassage>>? {
        val resolved = archive.resolvePath(path) ?: return null
        val item = archive.read(resolved) ?: return null
        if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return null
        val passages = HtmlPassages.split(firstLanguage(item.bytes.toString(Charsets.UTF_8)))
        return passages.takeIf { it.isNotEmpty() }?.let { resolved to it }
    }

    /** [html] up to its second h2 (the next language), without the quotations and examples nested in senses. */
    internal fun firstLanguage(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("li > ul, li > dl").remove()
        val next = doc.select("h2").getOrNull(1) ?: return doc.outerHtml()
        // The h2 sits in a heading wrapper (div.mw-heading) or straight in the content: it and everything after it
        // goes, at its own level and at each enclosing one.
        val start: Element = next.parent()?.takeIf { it.hasClass("mw-heading") } ?: next
        var level: Element? = start
        while (level != null && level !== doc.body()) {
            generateSequence(level.nextElementSibling()) { it.nextElementSibling() }.toList().forEach { it.remove() }
            level = level.parent()
        }
        start.remove()
        return doc.outerHtml()
    }

    /** The lemma an inflected form's entry names, or null when the entry is a lemma's own. */
    internal fun lemmaOf(passages: List<HtmlPassage>): String? {
        val first = passages.first()
        if (!FORM_HEADING.containsMatchIn(first.heading) && !FORM_TEXT.containsMatchIn(first.text)) return null
        return FORM_OF.find(first.text.trim())?.groupValues?.get(2)
    }

    /** Same word, another ending: one word, sharing all but its last three letters (at least four), folded. */
    internal fun sameStem(title: String, word: String): Boolean {
        val t = ResearchLanguage.fold(title.replace('_', ' ').trim())
        val w = ResearchLanguage.fold(word.trim())
        if (t.contains(' ') || t.length > w.length + 3) return false
        val shared = t.zip(w).takeWhile { (a, b) -> a == b }.size
        return shared >= maxOf(4, w.length - 3)
    }

    /**
     * The useful parts of an entry already cut to its first language ([firstLanguage]), "Heading: text", to
     * [MAX_CHARS]. A section split over several passages keeps one heading; the part that doesn't fit is cut at a word,
     * so a long etymology still leaves the senses some room.
     */
    internal fun text(passages: List<HtmlPassage>): String {
        val out = StringBuilder()
        var last: String? = null
        for (p in passages) {
            val heading = p.heading.trim()
            if (ResearchLanguage.fold(heading) in SKIP || p.text.isBlank()) continue
            val part = if (heading.isEmpty() || heading == last) p.text.trim() else "$heading: ${p.text.trim()}"
            last = heading
            val room = MAX_CHARS - out.length - (if (out.isEmpty()) 0 else 1)
            if (room < MIN_PART) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (part.length <= room) part else part.take(room - 2).substringBeforeLast(' ') + " …")
        }
        return out.toString()
    }

    private fun summary(path: String, title: String, passages: List<HtmlPassage>, prefix: String?): Found? {
        val body = text(passages).ifBlank { return null }
        return Found(path, title, if (prefix == null) body else prefix.trim() + "\n" + body)
    }
}
