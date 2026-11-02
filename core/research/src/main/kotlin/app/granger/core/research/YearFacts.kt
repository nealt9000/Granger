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
 *
 * Spanish (languages spec §6.2) adds its months ("18 de junio de 1815"), eras ("a. C."), ranges ("de 1810 a 1825",
 * "entre 1810 y 1825") and event words, when the question may be Spanish ([ResearchLanguage.of]). Only the parsing is
 * Spanish: the block is prompt text, and stays English until the Spanish eval has a baseline. A century ("siglo XIX",
 * like "19th century") gives no year.
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
    private val DASH = Regex("\\s*[–—]\\s*|(?<=\\d)-(?=\\d)")

    /** Spanish months, as Spanish writes them (lower case), or capitalised; "setiembre" is September too. */
    private val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val MONTH_NUMBERS: Map<String, Int> =
        (MONTH_NAMES.mapIndexed { i, m -> m to i + 1 } + MESES.mapIndexed { i, m -> m to i + 1 } + ("setiembre" to 9))
            .flatMap { (m, n) -> listOf(m to n, m.replaceFirstChar { it.uppercaseChar() } to n) }.toMap()
    private val MESES_PATTERN = (MESES + "setiembre").joinToString("|") { "[${it[0].uppercaseChar()}${it[0]}]${it.substring(1)}" }
    private const val Y = "(1\\d{3}|20\\d{2})"
    /** "de 1810 a 1825", "desde 1810 hasta 1825", "entre 1810 y 1825", "1810-1825": the last group matched is the end. */
    private val RANGE_ES = Regex("\\b(?:(?:de|desde) $Y (?:a|hasta) $Y|entre $Y y $Y|$Y\\s*[–—-]\\s*$Y)\\b")
    /** "a. C.", "a.C.", "a. de C.", "antes de Cristo", "a. e. c.", "AEC", "a. n. e.": a year BC isn't one we count. */
    private const val BC_ES = "\\s*(?:a\\.\\s?C\\.|a\\.\\s?de\\s?C\\.|antes de Cristo|a\\.\\s?e\\.\\s?c\\.|AEC\\b|a\\.\\s?n\\.\\s?e\\.)"
    /**
     * Sentences may open with ¿ ¡ « or an accented capital; "a. C.", "Sr." and the like don't end one, so "1000 a. C."
     * keeps its era.
     */
    private val SENTENCE_ES = Regex("(?<=[.!?])(?<!\\b(?:a|d|e|c|Sr|Sra|Dr|Dra|Sto|Sta|Ud|Uds)\\.)\\s+(?=[¿¡«\"'(\\[]?[\\p{Lu}0-9])")
    /** Spanish leads put the birthplace after the year: "(Alcalá de Henares, 29 de septiembre de 1547-Madrid, …)". */
    private val DASH_ES = Regex("\\s*[–—]\\s*|(?<=\\d)-(?=\\d)|(?<=\\d)-(?=\\p{Lu})")
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

    /**
     * A rewrite keyword made only of the words a question uses to ask how long or whether ("years passed", "how old"),
     * which no passage about the things themselves needs to hold (R2 Task 4: "years passed" kept every passage of the
     * Declaration and the Berlin Wall from matching all keywords, so ranking kept none). English words only.
     */
    fun cueOnly(keyword: String): Boolean {
        val words = WORD.findAll(keyword.lowercase()).map { it.value }.toList()
        return words.isNotEmpty() && words.all { it in STOP }
    }

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

    /** The Spanish words that ask how long or whether, folded. "Nació" and "murió" are the lifespan, as "born" and "died". */
    private val STOP_ES = setOf(
        "el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del", "al", "en", "que", "quien", "quienes",
        "cual", "cuales", "cuando", "donde", "como", "cuanto", "cuanta", "cuantos", "cuantas", "por", "para", "con", "sin",
        "sobre", "entre", "desde", "hasta", "tras", "se", "su", "sus", "le", "les", "fue", "fueron", "era", "eran", "es",
        "son", "ser", "sido", "estaba", "habia", "hubo", "haber", "pudo", "podria", "tenia", "tenian", "tiene", "tuvo",
        "edad", "anos", "ano", "antes", "despues", "pasaron", "paso", "pasado", "mismo", "misma", "tiempo", "aun",
        "todavia", "vivo", "viva", "vivia", "vivio", "vida", "mayor", "menor", "mas", "menos", "primero", "primera",
        "nacio", "nacido", "nacida", "nacimiento", "murio", "muerto", "muerta", "muerte", "fallecio", "fallecido",
        "fallecida", "diferencia", "ver", "alrededor", "entonces", "eso", "esto", "este", "esta", "ese", "esa", "ha",
        "han", "hay", "algo", "mientras", "aunque", "pero", "porque",
    )

    /** The Spanish question words and passage words for them, folded, as [GROUPS]. */
    private val GROUPS_ES = listOf(
        setOf(
            "salio", "publico", "publicada", "publicado", "publicacion", "publicar", "publicaron", "aparecio", "edito",
            "editada", "editado", "imprimio", "impresa", "impreso", "escribio", "escrita", "escrito", "estreno",
            "estrenada", "estrenado",
        ),
        setOf("llego", "llegaron", "llegada", "llegar", "alcanzo", "desembarco", "desembarcaron", "navego", "zarpo", "viaje", "viajes"),
        setOf("cayo", "caida", "derribado", "derribo", "demolido", "demolicion", "abrio", "apertura"),
        setOf("demostro", "demostracion", "demostrado", "probo", "prueba", "resolvio", "resuelto"),
        setOf("gano", "ganar", "gana", "ganador", "premio", "premiado", "recibio", "otorgado", "otorgo", "concedido", "galardonado"),
        setOf(
            "presidente", "presidencia", "investido", "elegido", "asumio", "goberno", "gobierno", "reino", "reinado",
            "coronado", "coronada", "coronacion",
        ),
        setOf("firmo", "firmado", "firmada", "adopto", "adoptada", "proclamo", "proclamada", "declaro", "declarada", "promulgo", "promulgada"),
        setOf(
            "construyo", "construido", "construida", "construccion", "fundo", "fundada", "fundado", "fundacion", "completado",
            "completada", "establecido", "establecida", "inauguro", "inaugurado", "inaugurada",
        ),
        setOf("descubrio", "descubierto", "descubrimiento", "invento", "inventado", "invencion"),
        setOf("comenzo", "comienzo", "empezo", "inicio", "iniciada", "iniciado"),
        setOf("termino", "finalizo", "fin", "acabo", "concluyo"),
        setOf("tabla", "periodica"),
    )
    private val ENDS_ES = setOf("cayo", "caida", "derribado", "derribo", "demolido", "demolicion", "termino", "finalizo", "fin", "acabo", "concluyo")

    /**
     * Spanish puts the verb first in "cuando salió el Quijote", "se publicó Pedro Páramo": a word after one of these
     * (and no name between) goes to the name after it, as an English -ing word does.
     */
    /** Between a word and the name after it: up to three words, then "de", "del" or "de la/los/las". */
    private val OF_NAME_ES = Regex("\\s+(?:\\p{L}+\\s+){0,3}?(?:de|del)(?: la| los| las)?\\s+")
    private val LIVE_TO_SEE_ES = Regex("\\bllego\\b(?=.*\\ba ver\\b)|\\ba ver\\b")
    private val FORWARD_ES = setOf("cuando", "se", "mientras", "tras", "hasta", "desde")

    /** The lists for a question's languages: English is the lists as they were; Spanish adds its own. */
    private class Rules(
        val date: Regex,
        val range: Regex,
        val bc: Regex,
        val sentence: Regex,
        val dash: Regex,
        val stop: Set<String>,
        val groups: List<Set<String>>,
        val ends: Set<String>,
        val english: Boolean,
        val spanish: Boolean,
        /** Spanish's verb-first words ([FORWARD_ES]), and whether "X de Name" gives X to the name. */
        val forward: Set<String> = emptySet(),
        val ofName: Boolean = false,
    )

    private val NEVER = "(?!)()()()"
    private val EN_RULES = Rules(DATE, RANGE, BC, SENTENCE, DASH, STOP, GROUPS, ENDS, english = true, spanish = false)
    private val ES_RULES = Rules(
        Regex("(?:(\\d{1,2}) (?:de )?($MESES_PATTERN) (?:de |del )?(\\d{3,4})|$NEVER|($MESES_PATTERN) (?:de |del )?(\\d{3,4})|\\b(\\d{3,4})\\b)"),
        RANGE_ES, Regex(BC_ES), SENTENCE_ES, DASH_ES, STOP_ES, GROUPS_ES, ENDS_ES, english = false, spanish = true,
        forward = FORWARD_ES, ofName = true,
    )
    private val DATE_BOTH = Regex(
        "(?:(\\d{1,2}) (?:de )?($MONTHS|$MESES_PATTERN) (?:de |del )?(\\d{3,4})|($MONTHS) (\\d{1,2}), (\\d{3,4})|" +
            "($MONTHS|$MESES_PATTERN) (?:de |del )?(\\d{3,4})|\\b(\\d{3,4})\\b)",
    )
    private val RANGE_BOTH = Regex(RANGE.pattern + "|" + RANGE_ES.pattern)
    private val BC_BOTH = Regex(BC.pattern + "|" + BC_ES)

    /** Spanish first ("¿Cuántos años tenía…?" with an English word): every list of both. */
    private val SPANISH_LEADS_RULES = Rules(
        DATE_BOTH, RANGE_BOTH, BC_BOTH, SENTENCE_ES, DASH_ES, STOP + STOP_ES, GROUPS + GROUPS_ES, ENDS + ENDS_ES,
        english = true, spanish = true, forward = FORWARD_ES, ofName = true,
    )

    /**
     * English first, Spanish possible: Spanish dates, eras and ranges (no English text reads like them), English
     * sentences and dashes, and only the Spanish words that aren't English too ([SpanishWords]).
     */
    private val ENGLISH_LEADS_RULES = Rules(
        DATE_BOTH, RANGE_BOTH, BC_BOTH, SENTENCE, DASH,
        STOP + STOP_ES.filterNot { it in SpanishWords.ENGLISH_TOO },
        GROUPS + GROUPS_ES.map { g -> g.filterNot { it in SpanishWords.ENGLISH_TOO }.toSet() },
        ENDS + ENDS_ES.filterNot { it in SpanishWords.ENGLISH_TOO },
        english = true, spanish = true, forward = FORWARD_ES.filterNot { it in SpanishWords.ENGLISH_TOO }.toSet(),
    )

    private fun rules(languages: List<ResearchLanguage>): Rules = when {
        ResearchLanguage.ES !in languages -> EN_RULES
        ResearchLanguage.EN !in languages -> ES_RULES
        SpanishWords.leads(languages) -> SPANISH_LEADS_RULES
        else -> ENGLISH_LEADS_RULES
    }

    /** Where a range's end year starts: the last group it matched ("1961 to 1989", "entre 1810 y 1825"). */
    private fun endAt(m: MatchResult): Int = m.groups.drop(1).last { it != null }!!.range.first

    /** The lines to add before the question, or null when fewer than two named things have dates. */
    fun block(
        question: String,
        keywords: List<String>,
        passages: List<Passage>,
        languages: List<ResearchLanguage> = ResearchLanguage.of(question),
    ): String? {
        val things = things(question, keywords, passages, languages)
        val worked = worked(things)
        if (worked.isEmpty()) return null
        return buildString {
            appendLine("Dates in the passages:")
            things.forEach { appendLine("- ${describe(it)}") }
            appendLine("Worked out from those dates:")
            worked.forEach { appendLine("- $it") }
            if (languages.firstOrNull() != ResearchLanguage.ES) verdict(question, things)?.let { appendLine("So, for the question: $it") }
        }.trimEnd()
    }

    private val OLDER = Regex("\\b(older|younger|oldest|youngest|born first|born earlier|born later)\\b", RegexOption.IGNORE_CASE)
    private val DIED_FIRST = Regex("\\b(died|die) (first|earlier|sooner)\\b", RegexOption.IGNORE_CASE)
    private val LIVED_LONGER = Regex("\\blived (longer|longest)\\b", RegexOption.IGNORE_CASE)
    private val WHETHER = Regex("^\\s*(could|did|was|were|would)\\b", RegexOption.IGNORE_CASE)

    /**
     * The worked lines' conclusion for the comparison the question asks, in words (R2 Task 3: E2B had "born on the same
     * day" and still said Lincoln was older; had "Jane Austen had died" and still declined "could she have read
     * Frankenstein"). Only the comparison words the block already treats as cues; no other reading of the question.
     * English only; null when the question asks none of these or the dates can't settle it.
     */
    internal fun verdict(question: String, things: List<Thing>): String? {
        val people = things.filter { it.born != null && it.event == null }
        if (people.size == 2) {
            val (a, b) = people
            when {
                OLDER.containsMatchIn(question) -> return olderOf(a, b)
                DIED_FIRST.containsMatchIn(question) && a.died != null && b.died != null -> {
                    val o = order(a.died, b.died)
                    if (o == 0 || a.died.year == b.died.year && !(a.died.exact && b.died.exact)) return null
                    val (first, second) = if (o < 0) a to b else b to a
                    return "${first.label} died first, before ${second.label} [${cite(a.lifeAt, b.lifeAt)}]."
                }
                LIVED_LONGER.containsMatchIn(question) && a.died != null && b.died != null -> {
                    val ageA = years(a.born!!, a.died).n
                    val ageB = years(b.born!!, b.died).n
                    if (ageA == ageB) return null
                    val (longer, shorter) = if (ageA > ageB) a to b else b to a
                    return "${longer.label} lived longer than ${shorter.label} [${cite(a.lifeAt, b.lifeAt)}]."
                }
            }
        }
        if (WHETHER.containsMatchIn(question)) {
            val person = things.firstOrNull { it.born != null && it.event == null } ?: return null
            val at = things.firstOrNull { it.event != null && it !== person } ?: return null
            val c = cite(person.lifeAt, at.eventAt)
            val then = "${on(at.event!!).lowercase()} (${at.label})"
            return when {
                order(at.event, person.born!!) < 0 -> "No: $then, ${person.label} was not yet born [$c]."
                person.died != null && order(at.event, person.died) > 0 ->
                    "No: $then, ${person.label} had already died (${person.died}) [$c]."
                else -> "Yes, as far as the dates go: $then, ${person.label} was alive [$c]."
            }
        }
        return null
    }

    private fun olderOf(a: Thing, b: Thing): String? {
        val x = a.born!!
        val y = b.born!!
        val c = cite(a.lifeAt, b.lifeAt)
        return when {
            x == y && x.exact -> "Neither is older: ${a.label} and ${b.label} were born on the same day, $x [$c]."
            x.year == y.year && !(x.exact && y.exact) -> null
            order(x, y) < 0 -> "${a.label} was older than ${b.label} [$c]."
            else -> "${b.label} was older than ${a.label} [$c]."
        }
    }

    /** The named things with dates, in the question's order, at most three. */
    fun things(
        question: String,
        keywords: List<String>,
        passages: List<Passage>,
        languages: List<ResearchLanguage> = ResearchLanguage.of(question),
    ): List<Thing> {
        val rules = rules(languages)
        // The question's own names; the rewrite's keywords only for a question typed in lower case ("periodic table"
        // is a keyword, but in the question it is what Mendeleev did).
        // A keyword must be in the question itself: "how old is the universe" was given "cosmology".
        val names = Entities.runs(question, languages).ifEmpty { keywords.filter { fold(question).contains(fold(it)) } }.map(::fold).distinct()
        val words = eventWords(question, names, languages)
        val texts = passages.map { it.text.take(SEEN_CHARS) }
        val out = mutableListOf<Thing>()
        for (name in names) {
            if (out.size == 3) break
            val articles = passages.indices.filter { passages[it].kind == SourceKind.ARTICLE && Entities.match(passages[it].sourceTitle, name, languages) > 0 }
            val label = articles.firstOrNull()?.let { passages[it].sourceTitle.substringBefore(" (") } ?: display(question, name)
            var thing = Thing(label)
            articles.firstNotNullOfOrNull { i ->
                if (Entities.match(passages[i].sourceTitle, name, languages) >= 2) lifespan(texts[i], languages)?.let { i to it } else null
            }?.let { (i, life) -> thing = thing.copy(born = life.first, died = life.second, lifeAt = i + 1) }
            val mine = words[name].orEmpty()
            val lifeYears = setOfNotNull(thing.born?.year, thing.died?.year)
            val event = if (mine.isNotEmpty()) eventFor(name, mine, articles, passages, texts, lifeYears, rules) else null
            thing = when {
                event != null -> thing.copy(event = event.first, eventAt = event.second, quote = event.third)
                // A work or an event with no sentence for its words: the first year of its lead, or the end of the
                // lead's first range for an end ("the Berlin Wall fell": "from 1961 to 1989").
                thing.born == null -> articles.firstNotNullOfOrNull { i ->
                    val first = rules.sentence.split(texts[i]).firstOrNull { YEAR.containsMatchIn(it) } ?: return@firstNotNullOfOrNull null
                    val range = if (mine.any { it in rules.ends }) rules.range.find(first) else null
                    val at = range?.let(::endAt) ?: YEAR.find(first)!!.range.first
                    if (rules.bc.matchesAt(first, at + 4)) null else dateIn(first, at, rules)?.let { Triple(it, i + 1, clause(first, at)) }
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
            // The question asks about this thing's event and the passages don't date it: its lifespan would answer
            // another question (r5-t4-facts: "In 1911, Mendeleev had died" for his periodic table; Columbus's death for
            // his landfall). Without it the block is left out rather than misleading.
            // Only a word of the groups asks for an event: "could Jane Austen have read" doesn't.
            if (mine.any { w -> rules.groups.any { w in it } } && thing.event == null && thing.born != null) return emptyList()
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
    fun lifespan(text: String, languages: List<ResearchLanguage> = Keywords.ENGLISH): Pair<Date, Date>? {
        val rules = rules(languages)
        val open = text.indexOf('(').takeIf { it in 0..400 } ?: return null
        var depth = 0
        var close = -1
        for (k in open until text.length) {
            if (text[k] == '(') depth++
            if (text[k] == ')' && --depth == 0) { close = k; break }
        }
        if (close < 0) return null
        val inside = SQUARE.replace(text.substring(open + 1, close), " ").replace(Regex("\\([^()]*\\)"), " ").replace(SPACES, " ")
        val dash = rules.dash.findAll(inside).lastOrNull { m ->
            YEAR.containsMatchIn(inside.substring(0, m.range.first)) && YEAR.containsMatchIn(inside.substring(m.range.last + 1))
        } ?: return null
        val left = inside.substring(0, dash.range.first)
        val right = inside.substring(dash.range.last + 1)
        val born = rules.date.findAll(left).mapNotNull { date(it) }.lastOrNull() ?: return null
        val died = rules.date.findAll(right).mapNotNull { date(it) }.firstOrNull() ?: return null
        return if (died.year >= born.year && died.year - born.year < 130) born to died else null
    }

    private fun date(m: MatchResult): Date? {
        val g = m.groupValues
        fun month(s: String) = MONTH_NUMBERS.getValue(s)
        return when {
            g[1].isNotEmpty() -> Date(g[3].toInt(), month(g[2]), g[1].toInt())
            g[4].isNotEmpty() -> Date(g[6].toInt(), month(g[4]), g[5].toInt())
            g[7].isNotEmpty() -> Date(g[8].toInt(), month(g[7]))
            g[9].isNotEmpty() -> Date(g[9].toInt()).takeIf { it.year >= 1000 }
            else -> null
        }
    }

    /** The full date around the year at [at] in [sentence] ("on 24 November 1859"), or the year alone. */
    private fun dateIn(sentence: String, at: Int, rules: Rules): Date? {
        val year = sentence.substring(at, at + 4).toIntOrNull() ?: return null
        return rules.date.findAll(sentence).mapNotNull { m -> date(m)?.takeIf { it.year == year && at in m.range } }.firstOrNull() ?: Date(year)
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
        rules: Rules,
    ): Triple<Date, Int, String>? {
        val groups = rules.groups
        val wanted = words + groups.filter { g -> words.any { it in g } }.flatten()
        var best: Triple<Int, Date, Pair<Int, String>>? = null
        for (i in passages.indices) {
            val own = i in articles
            for (s in rules.sentence.split(texts[i])) {
                if (!YEAR.containsMatchIn(s)) continue
                val folded = fold(s)
                if (!own && !folded.contains(name)) continue
                val found = WORD.findAll(folded).filter { it.value in wanted }.toList()
                if (found.isEmpty()) continue
                // Another article's sentence counts only on one of the groups' words: "bible" alone took the
                // Methuselah Foundation's 2003.
                if (!own && found.none { f -> groups.any { f.value in it } }) continue
                // Each of the question's words counts once, however many of its group the sentence has; the name's own
                // article wins a tie, then the earlier sentence (a lead's first edition before a later translation).
                val score = 2 * groups.count { g -> g.any { it in words } && found.any { it.value in g } } +
                    2 * found.count { f -> f.value in words && groups.none { f.value in it } } + (if (own) 1 else 0)
                if (best != null && score <= best.first) continue
                val range = if (found.any { it.value in rules.ends }) rules.range.find(s) else null
                val at = if (range != null) {
                    endAt(range)
                } else {
                    // In another article, the first year after the name: "Madoc ... sailed to the Americas in 1170, over
                    // 300 years before Christopher Columbus's voyage in 1492" is 1492 for Columbus.
                    val from = if (own) found.first().range.first else folded.indexOf(name)
                    (YEAR.findAll(s).firstOrNull { it.range.first > from } ?: if (own) YEAR.find(s) else null)?.range?.first ?: continue
                }
                // A person's own birth or death year is their lifespan, not the event; a year BC isn't one we can count.
                if (s.substring(at, at + 4).toInt() in lifeYears || rules.bc.matchesAt(s, at + 4)) continue
                val d = dateIn(s, at, rules) ?: continue
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
    fun eventWords(question: String, names: List<String>, languages: List<ResearchLanguage> = ResearchLanguage.of(question)): Map<String, Set<String>> {
        val rules = rules(languages)
        // "¿Llegó Darwin a ver…?" is "did Darwin live to see", not an arrival: blanked, so the places stay put.
        val q = fold(question).let { if (rules.spanish) LIVE_TO_SEE_ES.replace(it) { m -> " ".repeat(m.value.length) } else it }
        val nameWords = names.flatMap { it.split(' ') }.toSet()
        val at = names.mapNotNull { n -> q.indexOf(n).takeIf { it >= 0 }?.let { n to it } }.sortedBy { it.second }
        val out = HashMap<String, MutableSet<String>>()
        for (m in WORD.findAll(q)) {
            val w = m.value
            if (w in rules.stop || w in nameWords || w.length < 3) continue
            val beforeAt = at.lastOrNull { it.second < m.range.first }
            val before = beforeAt?.first
            val after = at.firstOrNull { it.second > m.range.first }?.first
            val afterAt = at.firstOrNull { it.second > m.range.first }?.second
            // Spanish: after "cuando", "se" and the like since the last name, the word is the next name's; so is one
            // that names it with "de" ("la batalla de Boyacá", "la tabla periódica de Mendeléyev").
            val forward = afterAt != null && (
                WORD.findAll(q.substring(beforeAt?.let { it.second + it.first.length }?.coerceAtMost(m.range.first) ?: 0, m.range.first))
                    .any { it.value in rules.forward } ||
                    (rules.ofName && afterAt > m.range.last && OF_NAME_ES.matches(q.substring(m.range.last + 1, afterAt)))
                )
            val owner = (if ((rules.english && w.endsWith("ing") && after != null) || forward) after else before ?: after) ?: continue
            out.getOrPut(owner) { mutableSetOf() } += w
        }
        return out
    }

    private fun display(question: String, name: String): String {
        val i = fold(question).indexOf(name)
        return if (i >= 0 && fold(question).length == question.length) question.substring(i, i + name.length) else name
    }

    private fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT).replace('’', '\'').replace(SPACES, " ")
}
