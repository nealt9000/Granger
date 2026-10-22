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
    /** [sentence] with up to [CONTEXT] characters of the passage on each side, for telling repeats apart. */
    data class Quote(val before: String, val sentence: String, val after: String)

    const val MIN_WORDS = 4
    const val MAX_CHARS = 240
    const val CONTEXT = 40

    private val group = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{M}+")

    /** Null when [text] has no words at all. */
    fun pick(answer: String, n: Int, text: String): Quote? {
        val sentences = sentences(text).filter { word.containsMatchIn(text.substring(it)) }
        if (sentences.isEmpty()) return null
        val claim = contentWords(claims(answer, n).joinToString(" ").replace(group, " "))
        val eligible = sentences.filter { word.findAll(text.substring(it)).count() >= MIN_WORDS }.ifEmpty { sentences }
        var best = eligible.first()
        var bestScore = 0.0
        for (range in eligible) {
            val words = contentWords(text.substring(range))
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
        val end = start + sentence.removeSuffix("…").length
        return Quote(
            before = text.substring((start - CONTEXT).coerceAtLeast(0), start),
            sentence = sentence,
            after = text.substring(end, (end + CONTEXT).coerceAtMost(text.length)),
        )
    }

    /** The answer's sentences and lines that cite [n]; the whole answer when none does. */
    internal fun claims(answer: String, n: Int): List<String> {
        val pieces = answer.split('\n').flatMap { line -> sentences(line).map { line.substring(it) } }
        val citing = pieces.filter { piece ->
            group.findAll(piece).any { m -> m.groupValues[1].split(',').any { it.trim().toIntOrNull() == n } }
        }
        return citing.ifEmpty { listOf(answer) }
    }

    /** Folded content words: lower case, accents off, no stopwords. */
    internal fun contentWords(s: String): Set<String> =
        word.findAll(fold(s)).map { it.value }.filterNot(Keywords::isStopword).toSet()

    internal fun fold(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    private fun sentences(text: String): List<IntRange> {
        val it = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        it.setText(text)
        val out = mutableListOf<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            if (text.substring(start, end).isNotBlank()) out += start until end
            start = end
            end = it.next()
        }
        return out
    }

    /** At most [MAX_CHARS], cut at a word with "…". */
    private fun cut(s: String): String {
        if (s.length <= MAX_CHARS) return s
        val head = s.substring(0, MAX_CHARS)
        val space = head.indexOfLast { it.isWhitespace() }
        return (if (space > 0) head.substring(0, space) else head).trimEnd() + "…"
    }
}
