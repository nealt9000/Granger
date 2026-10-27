package app.granger.core.research

import java.text.Normalizer
import java.util.Locale

/**
 * Plan R5, Task 4: year arithmetic in code. E2B reads the right dates out of the passages but gets the sums wrong
 * ("1492 to 1620 is 20 years"); given the sums worked out, it was grounded on 9 of 11 year questions instead of 2.
 * So for a question that compares dates, [block] finds the dates of the things it names in the numbered passages and
 * works out the gaps, ages and orders between them, each line citing the passages its dates came from.
 *
 * - A person's dates are the lifespan in their article's lead: "Mary Shelley (née Godwin; 30 August 1797 – 1 February 1851)".
 * - An event's year is in the sentence that shares the question's words for it ("reached", "fell", "proof"), each word
 *   belonging to the name it follows in the question ("Columbus reached", "the Berlin Wall fell"; "publishing X", the
 *   name after it). A work or event the question gives no words for takes the first year of its article's lead
 *   ("Frankenstein; or, The Modern Prometheus is an 1818 Gothic novel").
 *
 * Nothing is parsed from the question beyond the names and those words; the question's own numbers are never used.
 */
object YearFacts {
    /** A date as precise as the passage gives it; [month] and [day] are 0 when it doesn't. */
    data class Date(val year: Int, val month: Int = 0, val day: Int = 0) {
        val exact: Boolean get() = day > 0
        override fun toString(): String = when {
            day > 0 -> "$day ${MONTH_NAMES[month - 1]} $year"
            month > 0 -> "${MONTH_NAMES[month - 1]} $year"
            else -> "$year"
        }
    }

    /** What the passages say of one named thing: a lifespan from passage [lifeAt], an event from [eventAt] (1-based). */
    data class Thing(
        val label: String,
        val born: Date? = null,
        val died: Date? = null,
        val lifeAt: Int = 0,
        val event: Date? = null,
        val eventAt: Int = 0,
        val quote: String = "",
    )

    private val MONTH_NAMES = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )
    private val MONTHS = MONTH_NAMES.joinToString("|")
    private val DATE = Regex("(?:(\\d{1,2}) ($MONTHS) (\\d{3,4})|($MONTHS) (\\d{1,2}), (\\d{3,4})|($MONTHS) (\\d{3,4})|\\b(\\d{3,4})\\b)")
    private val YEAR = Regex("\\b(1\\d{3}|20\\d{2})\\b")
    private val RANGE = Regex("\\b(?:from )?(1\\d{3}|20\\d{2})\\s*(?:–|-|—| to | until )\\s*(1\\d{3}|20\\d{2})\\b")
    private val BC = Regex("\\s*(BC|BCE|B\\.C\\.)\\b")
    private val SQUARE = Regex("\\[[^\\]]*\\]")
    private val SENTENCE = Regex("(?<=[.!?])\\s+(?=[\"'(\\[]?[A-Z0-9])")
    private val WORD = Regex("[\\p{L}]+")
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /** The prompt cuts each passage near this many characters; a date past it isn't in front of the model. */
    private const val SEEN_CHARS = 1_190

    /** Words that ask how long or whether, not about any one thing. */
    private val STOP = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "was", "were", "is", "are", "did", "does", "do", "how", "old",
        "when", "what", "who", "which", "many", "years", "year", "after", "before", "between", "passed", "long", "still",
        "already", "alive", "same", "time", "could", "have", "has", "had", "and", "or", "by", "much", "live", "lived",
        "see", "her", "his", "their", "its", "it", "he", "she", "they", "first", "around", "older", "younger", "longer",
        "if", "than", "then", "there", "that", "this", "for", "with", "as", "be", "been", "about", "get", "die", "died",
        "dead", "born",
    )

    /** A question word and the words a passage may use for it. */
    private val GROUPS = listOf(
        setOf("came", "publish", "published", "publishing", "publication", "appeared", "released", "release", "printed", "written", "wrote"),
        setOf("arrive", "arrived", "arrival", "reached", "reach", "reaching", "landed", "landfall", "landing", "sailed", "transported", "voyage", "voyages"),
        setOf("fell", "fall", "fallen", "demolished", "opened", "breach"),
        setOf("proved", "proof", "proven", "prove", "proving", "solved"),
        setOf("win", "won", "winning", "awarded", "received", "prize"),
        setOf("president", "presidency", "inaugurated", "elected", "serving", "served"),
        setOf("signed", "adopted", "sealed", "declared", "issued"),
        setOf("built", "founded", "constructed", "completed", "established"),
        setOf("periodic", "table"),
    )
    /** Words for an end: a range's last year is the one asked about ("the Wall fell": 1961 to 1989). */
    private val ENDS = setOf("fell", "fall", "fallen", "demolished", "ended", "end", "ending", "collapse", "collapsed", "breach")

    /** The lines to add before the question, or null when fewer than two named things have dates. */
    fun block(question: String, keywords: List<String>, passages: List<Passage>): String? {
        val things = things(question, keywords, passages)
        val worked = worked(things)
        if (worked.isEmpty()) return null
        return buildString {
            appendLine("Dates in the passages:")
            things.forEach { appendLine("- ${describe(it)}") }
            appendLine("Worked out from those dates:")
            worked.forEach { appendLine("- $it") }
        }.trimEnd()
    }

    /** The named things with dates, in the question's order, at most three. */
    fun things(question: String, keywords: List<String>, passages: List<Passage>): List<Thing> {
        // The question's own names; the rewrite's keywords only for a question typed in lower case ("periodic table"
        // is a keyword, but in the question it is what Mendeleev did).
        val names = Entities.runs(question).ifEmpty { keywords }.map(::fold).distinct()
        val words = eventWords(question, names)
        val texts = passages.map { it.text.take(SEEN_CHARS) }
        val out = mutableListOf<Thing>()
        for (name in names) {
            if (out.size == 3) break
            val articles = passages.indices.filter { passages[it].kind == SourceKind.ARTICLE && Entities.match(passages[it].sourceTitle, name) > 0 }
            val label = articles.firstOrNull()?.let { passages[it].sourceTitle.substringBefore(" (") } ?: display(question, name)
            var thing = Thing(label)
            articles.firstNotNullOfOrNull { i ->
                if (Entities.match(passages[i].sourceTitle, name) >= 2) lifespan(texts[i])?.let { i to it } else null
            }?.let { (i, life) -> thing = thing.copy(born = life.first, died = life.second, lifeAt = i + 1) }
            val mine = words[name].orEmpty()
            val lifeYears = setOfNotNull(thing.born?.year, thing.died?.year)
            val event = if (mine.isNotEmpty()) eventFor(name, mine, articles, passages, texts, lifeYears) else null
            thing = when {
                event != null -> thing.copy(event = event.first, eventAt = event.second, quote = event.third)
                // A work or an event with no sentence for its words: the first year of its lead, or the end of the
                // lead's first range for an end ("the Berlin Wall fell": "from 1961 to 1989").
                thing.born == null -> articles.firstNotNullOfOrNull { i ->
                    val first = SENTENCE.split(texts[i]).firstOrNull { YEAR.containsMatchIn(it) } ?: return@firstNotNullOfOrNull null
                    val range = if (mine.any { it in ENDS }) RANGE.find(first) else null
                    val at = range?.groups?.get(2)?.range?.first ?: YEAR.find(first)!!.range.first
                    if (BC.matchesAt(first, at + 4)) null else dateIn(first, at)?.let { Triple(it, i + 1, clause(first, at)) }
                }?.let { thing.copy(event = it.first, eventAt = it.second, quote = it.third) } ?: thing
                else -> thing
            }
            // Two names for one article ("darwin", "charles darwin") are one thing.
            if (out.any { it.label == thing.label }) continue
            // The same sentence twice is one event ("Columbus ... landfall in the Americas" for both names).
            if (thing.event != null && out.any { it.event == thing.event && it.eventAt == thing.eventAt && it.quote == thing.quote }) {
                thing = thing.copy(event = null, eventAt = 0, quote = "")
            }
            // A person's event on their birth or death year adds nothing to the lifespan.
            if (thing.event != null && (thing.event!!.year == thing.born?.year || thing.event!!.year == thing.died?.year) && thing.born != null) {
                thing = thing.copy(event = null, eventAt = 0, quote = "")
            }
            if (thing.born != null || thing.event != null) out += thing
        }
        return out
    }

    /** The gaps, ages and orders between [things], one line each. */
    fun worked(things: List<Thing>): List<String> {
        val lines = mutableListOf<String>()
        for (i in things.indices) for (j in i + 1 until things.size) {
            val a = things[i]
            val b = things[j]
            // Two events are compared with each other; a person is set against the other's event only when the person
            // has none of their own ("In 1620 (Mayflower), Columbus had died" says nothing about 1492).
            if (a.event != null && b.event != null) lines += between(a, b)
            if (a.born != null && a.event == null && b.event != null) lines += alive(a, b)
            if (b.born != null && b.event == null && a.event != null) lines += alive(b, a)
            if (a.born != null && b.born != null && a.event == null && b.event == null) {
                lines += compare(a, b, "born", a.born, b.born, a.lifeAt, b.lifeAt)
                if (a.died != null && b.died != null) lines += compare(a, b, "died", a.died, b.died, a.lifeAt, b.lifeAt)
                val ageA = a.died?.let { years(a.born, it) }
                val ageB = b.died?.let { years(b.born, it) }
                if (ageA != null && ageB != null) lines += "${a.label} lived to $ageA and ${b.label} to $ageB [${cite(a.lifeAt, b.lifeAt)}]."
            }
        }
        return lines
    }

    private fun describe(t: Thing): String = buildString {
        append(t.label).append(": ")
        val parts = mutableListOf<String>()
        if (t.born != null) parts += "born ${t.born}" + (t.died?.let { ", died $it" } ?: "") + " [${t.lifeAt}]"
        if (t.event != null) parts += "${t.event} [${t.eventAt}] (\"${t.quote}\")"
        append(parts.joinToString("; "))
    }

    private fun between(a: Thing, b: Thing): String {
        val (first, second) = if (order(a.event!!, b.event!!) <= 0) a to b else b to a
        val gap = years(first.event!!, second.event!!)
        val c = cite(first.eventAt, second.eventAt)
        return when {
            first.event!!.year == second.event!!.year -> "${first.label} (${first.event}) and ${second.label} (${second.event}) are the same year [$c]."
            else -> "${second.label} (${second.event}) came ${gap.phrase()} after ${first.label} (${first.event}) [$c]."
        }
    }

    private fun alive(person: Thing, at: Thing): String {
        val when_ = at.event!!
        val c = cite(person.lifeAt, at.eventAt)
        return when {
            order(when_, person.born!!) < 0 ->
                "${on(when_)} (${at.label}), ${person.label} was not yet born: born ${years(when_, person.born).phrase()} later [$c]."
            person.died != null && order(when_, person.died) > 0 ->
                "${on(when_)} (${at.label}), ${person.label} had died, ${years(person.died, when_).phrase()} before (${person.died}) [$c]."
            else -> {
                val age = years(person.born, when_)
                val left = person.died?.let { ", and died ${years(when_, it).phrase()} later (${it})" } ?: ""
                "${on(when_)} (${at.label}), ${person.label} was alive, aged $age$left [$c]."
            }
        }
    }

    private fun compare(a: Thing, b: Thing, what: String, x: Date, y: Date, an: Int, bn: Int): String {
        val c = cite(an, bn)
        val o = order(x, y)
        return when {
            x == y && x.exact -> "${a.label} and ${b.label} ${verbs(what)} on the same day, $x [$c]."
            x.year == y.year && x.month > 0 && x.month == y.month && x.exact && y.exact ->
                "${a.label} ${verb(what)} on $x and ${b.label} on $y: ${days(kotlin.math.abs(x.day - y.day))} apart [$c]."
            x.year == y.year -> "${a.label} ($x) and ${b.label} ($y) ${verbs(what)} in the same year [$c]."
            o < 0 -> "${a.label} ${verb(what)} ${years(x, y).phrase()} before ${b.label} ($x, $y) [$c]."
            else -> "${b.label} ${verb(what)} ${years(y, x).phrase()} before ${a.label} ($y, $x) [$c]."
        }
    }

    private fun on(d: Date) = if (d.exact) "On $d" else "In $d"
    private fun verb(what: String) = if (what == "born") "was born" else "died"
    private fun verbs(what: String) = if (what == "born") "were born" else "died"
    private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    /** Whole years from [from] to [to]: exact when both have a day, else "N or N−1" collapsed to the year difference's floor. */
    private class Years(val n: Int, val exact: Boolean) {
        fun phrase(): String = if (n == 1) "1 year" else if (exact) "$n years" else "about $n years"
        override fun toString(): String = if (exact) "$n" else "${n - 1} or $n"
    }

    private fun years(from: Date, to: Date): Years {
        val raw = to.year - from.year
        if (!(from.exact && to.exact)) return Years(raw, false)
        val before = to.month < from.month || (to.month == from.month && to.day < from.day)
        return Years(if (before) raw - 1 else raw, true)
    }

    private fun order(a: Date, b: Date): Int = compareValuesBy(a, b, { it.year }, { it.month }, { it.day })

    private fun cite(vararg n: Int): String = n.filter { it > 0 }.distinct().sorted().joinToString("][")

    /** "(… 30 August 1797 – 1 February 1851)" or "(1775–1817)": the first bracket of a lead with a date each side of a dash. */
    fun lifespan(text: String): Pair<Date, Date>? {
        val open = text.indexOf('(').takeIf { it in 0..400 } ?: return null
        var depth = 0
        var close = -1
        for (k in open until text.length) {
            if (text[k] == '(') depth++
            if (text[k] == ')' && --depth == 0) { close = k; break }
        }
        if (close < 0) return null
        val inside = SQUARE.replace(text.substring(open + 1, close), " ").replace(Regex("\\([^()]*\\)"), " ").replace(SPACES, " ")
        val dash = Regex("\\s*[–—]\\s*|(?<=\\d)-(?=\\d)").findAll(inside).lastOrNull { m ->
            YEAR.containsMatchIn(inside.substring(0, m.range.first)) && YEAR.containsMatchIn(inside.substring(m.range.last + 1))
        } ?: return null
        val left = inside.substring(0, dash.range.first)
        val right = inside.substring(dash.range.last + 1)
        val born = DATE.findAll(left).mapNotNull { date(it) }.lastOrNull() ?: return null
        val died = DATE.findAll(right).mapNotNull { date(it) }.firstOrNull() ?: return null
        return if (died.year >= born.year && died.year - born.year < 130) born to died else null
    }

    private fun date(m: MatchResult): Date? {
        val g = m.groupValues
        fun month(s: String) = MONTH_NAMES.indexOf(s) + 1
        return when {
            g[1].isNotEmpty() -> Date(g[3].toInt(), month(g[2]), g[1].toInt())
            g[4].isNotEmpty() -> Date(g[6].toInt(), month(g[4]), g[5].toInt())
            g[7].isNotEmpty() -> Date(g[8].toInt(), month(g[7]))
            g[9].isNotEmpty() -> Date(g[9].toInt()).takeIf { it.year >= 1000 }
            else -> null
        }
    }

    /** The full date around the year at [at] in [sentence] ("on 24 November 1859"), or the year alone. */
    private fun dateIn(sentence: String, at: Int): Date? {
        val year = sentence.substring(at, at + 4).toIntOrNull() ?: return null
        return DATE.findAll(sentence).mapNotNull { m -> date(m)?.takeIf { it.year == year && at in m.range } }.firstOrNull() ?: Date(year)
    }

    /**
     * The best sentence for [name]'s [words]: in its own articles, or naming it anywhere. Scored by the words it shares
     * with them; its year is a range's end for an end word, else the nearest year after the first shared word.
     */
    private fun eventFor(
        name: String,
        words: Set<String>,
        articles: List<Int>,
        passages: List<Passage>,
        texts: List<String>,
        lifeYears: Set<Int>,
    ): Triple<Date, Int, String>? {
        val wanted = expand(words)
        var best: Triple<Int, Date, Pair<Int, String>>? = null
        for (i in passages.indices) {
            val own = i in articles
            for (s in SENTENCE.split(texts[i])) {
                if (!YEAR.containsMatchIn(s)) continue
                val folded = fold(s)
                if (!own && !folded.contains(name)) continue
                val found = WORD.findAll(folded).filter { it.value in wanted }.toList()
                if (found.isEmpty()) continue
                // Another article's sentence counts only on one of the groups' words: "bible" alone took the
                // Methuselah Foundation's 2003.
                if (!own && found.none { f -> GROUPS.any { f.value in it } }) continue
                // Each of the question's words counts once, however many of its group the sentence has; the name's own
                // article wins a tie, then the earlier sentence (a lead's first edition before a later translation).
                val score = 2 * GROUPS.count { g -> g.any { it in words } && found.any { it.value in g } } +
                    2 * found.count { f -> f.value in words && GROUPS.none { f.value in it } } + (if (own) 1 else 0)
                if (best != null && score <= best.first) continue
                val range = if (found.any { it.value in ENDS }) RANGE.find(s) else null
                val at = if (range != null) {
                    range.groups[2]!!.range.first
                } else {
                    val after = YEAR.findAll(s).firstOrNull { it.range.first > found.first().range.first }
                    (after ?: YEAR.find(s)!!).range.first
                }
                // In another article the name comes before the year: "Madoc ... sailed to the Americas in 1170, over 300
                // years before Christopher Columbus" is Madoc's date, not Columbus's.
                if (!own && folded.indexOf(name) > at) continue
                // A person's own birth or death year is their lifespan, not the event; a year BC isn't one we can count.
                if (s.substring(at, at + 4).toInt() in lifeYears || BC.matchesAt(s, at + 4)) continue
                val d = dateIn(s, at) ?: continue
                best = Triple(score, d, (i + 1) to clause(s, at))
            }
        }
        return best?.let { Triple(it.second, it.third.first, it.third.second) }
    }

    /** About 90 characters of [s] around [at], cut at words. */
    private fun clause(s: String, at: Int): String {
        val start = (at - 60).coerceAtLeast(0).let { k -> if (k == 0) 0 else s.indexOf(' ', k).let { if (it < 0 || it > at) k else it + 1 } }
        val end = (at + 30).coerceAtMost(s.length).let { k -> if (k == s.length) k else s.lastIndexOf(' ', k).let { if (it <= at) k else it } }
        return (if (start > 0) "…" else "") + s.substring(start, end).trim().trimEnd('.', ',', ';') + (if (end < s.length) "…" else "")
    }

    /**
     * Each of the question's content words, given to a name: the one it follows ("Columbus reached"), or for an -ing
     * word the one after it ("publishing On the Origin of Species"); before any name, the first name after it.
     */
    fun eventWords(question: String, names: List<String>): Map<String, Set<String>> {
        val q = fold(question)
        val nameWords = names.flatMap { it.split(' ') }.toSet()
        val at = names.mapNotNull { n -> q.indexOf(n).takeIf { it >= 0 }?.let { n to it } }.sortedBy { it.second }
        val out = HashMap<String, MutableSet<String>>()
        for (m in WORD.findAll(q)) {
            val w = m.value
            if (w in STOP || w in nameWords || w.length < 3) continue
            val before = at.lastOrNull { it.second < m.range.first }?.first
            val after = at.firstOrNull { it.second > m.range.first }?.first
            val owner = (if (w.endsWith("ing") && after != null) after else before ?: after) ?: continue
            out.getOrPut(owner) { mutableSetOf() } += w
        }
        return out
    }

    private fun expand(words: Set<String>): Set<String> = words + GROUPS.filter { g -> words.any { it in g } }.flatten()

    private fun display(question: String, name: String): String {
        val i = fold(question).indexOf(name)
        return if (i >= 0 && fold(question).length == question.length) question.substring(i, i + name.length) else name
    }

    private fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).replace('’', '\'').replace(SPACES, " ")
}
