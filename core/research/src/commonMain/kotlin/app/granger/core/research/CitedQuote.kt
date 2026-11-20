package app.granger.core.research

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * The sentence of a cited passage that best matches what the answer said about it, so a citation opens on the words
 * the answer used rather than the passage's first line (research spec §7).
 *
 * The claim is every answer sentence (or list line) citing `[n]`, or the whole answer when none does. Words are folded
 * (lower case, accents off) and stopwords dropped. Each passage sentence of at least [MIN_WORDS] words scores its
 * content words shared with the claim over √(its content words); ties go to the earlier one, and with nothing shared
 * the first such sentence wins.
 */
object CitedQuote {
    /**
     * [sentence] with up to [CONTEXT] characters of the passage on each side, for telling repeats apart. Context never
     * crosses a paragraph break (`\n` in passage text), since the page's text has none there.
     */
    data class Quote(val before: String, val sentence: String, val after: String)

    const val MIN_WORDS = 4
    const val MAX_CHARS = 240
    const val CONTEXT = 40

    private val group = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{M}+")
    private val SPANISH = Locale.forLanguageTag("es")

    /**
     * Null when [text] has no words at all. [languages] are the question's ([ResearchLanguage.of]), English alone by
     * default; [passageLanguage] is the passage's, when known. The passage's sentences break by its language, or else
     * the question's main one; the answer's by the question's; stopwords are those of every language named.
     */
    fun pick(
        answer: String,
        n: Int,
        text: String,
        languages: List<ResearchLanguage> = Keywords.ENGLISH,
        passageLanguage: ResearchLanguage? = null,
    ): Quote? {
        val sentences = sentences(text, passageLanguage ?: languages.first()).filter { word.containsMatchIn(text.substring(it)) }
        if (sentences.isEmpty()) return null
        val stops = (languages + listOfNotNull(passageLanguage)).distinct()
        val claim = contentWords(claims(answer, n, languages.first()).joinToString(" ").replace(group, " "), stops)
        val eligible = sentences.filter { word.findAll(text.substring(it)).count() >= MIN_WORDS }.ifEmpty { sentences }
        var best = eligible.first()
        var bestScore = 0.0
        for (range in eligible) {
            val words = contentWords(text.substring(range), stops)
            if (words.isEmpty()) continue
            val score = words.count { it in claim } / sqrt(words.size.toDouble())
            if (score > bestScore) {
                best = range
                bestScore = score
            }
        }
        val raw = text.substring(best)
        val start = best.first + (raw.length - raw.trimStart().length)
        val sentence = cut(raw.trim())
        val end = start + sentence.length
        val paragraphStart = text.lastIndexOf('\n', start - 1) + 1
        val paragraphEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return Quote(
            before = text.substring((start - CONTEXT).coerceAtLeast(paragraphStart), start),
            sentence = sentence,
            after = text.substring(end, (end + CONTEXT).coerceAtMost(paragraphEnd)),
        )
    }

    /** The answer's sentences and lines that cite [n]; the whole answer when none does. */
    internal fun claims(answer: String, n: Int, language: ResearchLanguage = ResearchLanguage.EN): List<String> {
        val pieces = answer.split('\n').flatMap { line -> sentences(line, language).map { line.substring(it) } }
        val citing = pieces.filter { piece ->
            group.findAll(piece).any { m -> m.groupValues[1].split(',').any { it.trim().toIntOrNull() == n } }
        }
        return citing.ifEmpty { listOf(answer) }
    }

    /** Folded content words: lower case, accents off, no stopwords and nothing under three letters (as [Keywords.extract]). */
    internal fun contentWords(s: String, languages: List<ResearchLanguage> = Keywords.ENGLISH): Set<String> =
        word.findAll(fold(s)).map { it.value }.filter { it.length >= 3 && !Keywords.isStopword(it, languages) }.toSet()

    internal fun fold(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Sentence ranges in [text], paragraph by paragraph: a sentence never runs across a `\n`. Breaks by [language]'s rules. */
    internal fun sentences(text: String, language: ResearchLanguage = ResearchLanguage.EN): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val it = BreakIterator.getSentenceInstance(if (language == ResearchLanguage.ES) SPANISH else Locale.ENGLISH)
        var from = 0
        while (from <= text.length) {
            val to = text.indexOf('\n', from).let { i -> if (i < 0) text.length else i }
            val paragraph = text.substring(from, to)
            it.setText(paragraph)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                // Trailing whitespace stays out, so a range ends on the sentence's last character.
                val piece = paragraph.substring(start, end)
                if (piece.isNotBlank()) out += (from + start) until (from + start + piece.trimEnd().length)
                start = end
                end = it.next()
            }
            from = to + 1
        }
        return out
    }

    /** At most [MAX_CHARS], cut at a word; no "…", since the quote is matched against the page, not shown. */
    private fun cut(s: String): String {
        if (s.length <= MAX_CHARS) return s
        val head = s.substring(0, MAX_CHARS)
        val space = head.indexOfLast { it.isWhitespace() }
        return (if (space > 0) head.substring(0, space) else head).trimEnd()
    }
}
