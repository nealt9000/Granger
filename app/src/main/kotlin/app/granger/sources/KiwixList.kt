package app.granger.sources

import app.granger.core.catalog.PackEntry
import app.granger.core.library.SearchText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * One pack of the Kiwix list, whatever its flavours (Plan KB Decision 8): every catalog entry with the same ZIM name,
 * [flavours] with pictures first, and the one shown by [default].
 */
data class PackGroup(
    val zimName: String,
    val flavours: List<PackEntry>,
    val default: PackEntry,
    /** A set's one row (Plan WF Decision 3): [flavours] are every flavour of every slice, smallest slice first. */
    val setId: String? = null,
) {
    /** The pack's name without a flavour's own words: "WikiMed", not "WikiMed (no pictures)"; a set's own name. */
    val title: String get() = KiwixList.groupTitle(this)

    val isSet: Boolean get() = setId != null

    val featured: Boolean get() = flavours.any { it.featured }

    fun flavour(id: String): PackEntry? = flavours.firstOrNull { it.id == id }
}

/** The Kiwix list's packs, grouped and in plain words (Plan KB Decisions 8 and 9). Pure. */
object KiwixList {
    /** Flavours in the order they're offered: with pictures, without, mini, then an unflavoured ZIM. */
    private val FLAVOUR_ORDER = listOf("maxi", "nopic", "mini", "")

    /** A Get over this asks first, with the free space and a smaller flavour (Plan KB Decision 9): 10 GB. */
    const val SIZE_CHECK_BYTES = 10L * 1000 * 1000 * 1000

    /** The true flavours, the only entries of one ZIM name that group (Plan KB walkthrough 2). */
    private val FLAVOURS = setOf("maxi", "nopic", "mini")

    /**
     * [entry]'s flavour: the catalog's, or for a catalog without the field (main's, before Plan KB) the one its id ends
     * in, `wikipedia_en_top_nopic` → "nopic"; blank for an unflavoured ZIM.
     */
    fun flavourOf(entry: PackEntry): String {
        if (entry.flavour.isNotBlank()) return entry.flavour
        val suffix = entry.id.removePrefix("${entry.zimName}_").takeIf { entry.zimName.isNotBlank() && it != entry.id }
        return suffix?.takeIf { it in FLAVOURS }.orEmpty()
    }

    const val WITH_PICTURES = "With pictures"
    const val NO_PICTURES = "No pictures"
    const val MINI = "Mini (intro paragraphs only)"

    /** What a flavour is called on a row: [WITH_PICTURES], [NO_PICTURES], [MINI]; "Standard" for an unflavoured ZIM. */
    fun flavourLabel(flavour: String): String = when (flavour) {
        "maxi" -> WITH_PICTURES
        "nopic" -> NO_PICTURES
        "mini" -> MINI
        else -> "Standard"
    }

    /** [entry]'s flavour as a row says it. */
    fun flavourLabel(entry: PackEntry): String = flavourLabel(flavourOf(entry))

    /**
     * Each of [group]'s flavours as its choice says it, by id, no two the same (Plan KB walkthrough 2): the flavour, and
     * where two share one, what else differs: the month updated, the language, the title, else the id.
     */
    fun flavourLabels(group: PackGroup): Map<String, String> {
        val labels = group.flavours.associate { it.id to flavourLabel(it) }.toMutableMap()
        val distinctions = listOf<(PackEntry) -> String>(
            { updated(it.snapshotDate) },
            { e -> e.language.split(',').joinToString(", ") { languageName(it) } },
            { it.title },
            { it.id },
        )
        for ((label, same) in group.flavours.groupBy { labels.getValue(it.id) }) {
            if (same.size < 2) continue
            val by = distinctions.firstOrNull { d -> same.map(d).distinct().size == same.size } ?: distinctions.last()
            for (entry in same) labels[entry.id] = "$label · ${by(entry)}"
        }
        return labels
    }

    /**
     * [packs] grouped by ZIM name, in the order each name first appears; only true flavours group, so an unflavoured ZIM
     * that shares its name with flavoured ones is a pack of its own. A group's default is its featured flavour (the
     * largest featured that fits in [freeBytes], else the smallest featured), else the largest flavour that fits, else
     * the smallest; with [freeBytes] null every flavour fits.
     */
    fun group(packs: List<PackEntry>, freeBytes: Long? = null, sets: Boolean = false): List<PackGroup> {
        // Keyed by ZIM name for a flavour, by id (apart from any ZIM name) for anything else.
        val groups = packs.groupBy { if (it.zimName.isNotBlank() && flavourOf(it).isNotBlank()) "zim:${it.zimName}" else "id:${it.id}" }.values.map { entries ->
            val flavours = entries.sortedBy(::flavourRank)
            PackGroup(flavours.first().zimName.ifBlank { flavours.first().id }, flavours, defaultOf(flavours, freeBytes))
        }
        return if (sets) mergeSets(groups, freeBytes) else groups
    }

    private fun flavourRank(entry: PackEntry) = FLAVOUR_ORDER.indexOf(flavourOf(entry)).let { i -> if (i < 0) FLAVOUR_ORDER.size else i }

    /** [groups] with each set's slices made one group, at the place of its first slice (Plan WF Decision 3). */
    private fun mergeSets(groups: List<PackGroup>, freeBytes: Long?): List<PackGroup> {
        val bySet = groups.filter { g -> g.flavours.all { it.isSlice } }.groupBy { it.default.setId }
        val placed = HashSet<String>()
        return groups.mapNotNull { g ->
            val setId = g.default.setId.takeIf { g.flavours.all { it.isSlice } } ?: return@mapNotNull g
            if (!placed.add(setId)) return@mapNotNull null
            val flavours = bySet.getValue(setId).flatMap { it.flavours }.sortedWith(compareBy({ it.setRank }, ::flavourRank))
            PackGroup("set:$setId", flavours, defaultOf(flavours, freeBytes), setId)
        }
    }

    // --- Sets (Plan WF) ---

    /** A set's name and line, by set id; a set this doesn't know takes its slices' title before the colon. */
    private val SET_TEXT = mapOf(
        "wikipedia_en" to ("Wikipedia" to "The free encyclopedia, from 100 essential articles to all of it."),
        "wikivoyage_en" to ("Wikivoyage" to "The free travel guide, from Europe to the whole world."),
    )

    fun setTitle(setId: String, entries: List<PackEntry>): String =
        SET_TEXT[setId]?.first ?: entries.firstOrNull()?.title?.substringBefore(": ").orEmpty()

    fun setDescription(setId: String, entries: List<PackEntry>): String =
        SET_TEXT[setId]?.second ?: entries.maxByOrNull { it.setRank }?.description.orEmpty()

    /** One slice of a set's row: its ZIM name, rank, label and flavours (with pictures first). */
    data class Slice(val zimName: String, val rank: Int, val label: String, val entries: List<PackEntry>)

    /** [group]'s slices, smallest first. */
    fun slices(group: PackGroup): List<Slice> = group.flavours.groupBy { it.zimName }.values
        .map { e -> Slice(e.first().zimName, e.first().setRank, e.first().setLabel, e.sortedBy(::flavourRank)) }
        .sortedBy { it.rank }

    /** The pictures choice a slice entry is: its flavour, an unflavoured slice (the 100) counting as with pictures. */
    fun picturesOf(entry: PackEntry): String = flavourOf(entry).ifBlank { "maxi" }

    /** [slice]'s entry for the [pictures] choice, else its first (it doesn't offer that choice). */
    fun entryFor(slice: Slice, pictures: String): PackEntry =
        slice.entries.firstOrNull { picturesOf(it) == pictures } ?: slice.entries.first()

    /** A slice's name in a sentence: "Best 50,000", "Top 1 million", "Everything". */
    fun shortLabel(label: String): String = label.removeSuffix(" articles")

    /** Whether [slice] (of rank [slice].setRank, the set's top rank [topRank]) holds [subject]'s articles. */
    fun covers(slice: PackEntry, subject: PackEntry, topRank: Int): Boolean =
        slice.setRank >= topRank || (subject.adds[slice.zimName]?.let { it < app.granger.core.catalog.COVERED_SHARE } ?: false)

    /** What a subject's row says about [slice] (Plan WF Decision 4): its [text], and whether it is [covered] already. */
    data class Adds(val text: String, val covered: Boolean)

    /**
     * "Adds about 69,600 articles to your Best 50,000 (95% new)" against the [installed] slice, or "… to Top 1 million …"
     * against a chosen one; "Already inside your Wikipedia" when the slice covers it. Null when the catalog doesn't say.
     */
    fun adds(subject: PackEntry, slice: PackEntry, topRank: Int, setTitle: String, installed: Boolean): Adds? {
        val name = shortLabel(slice.setLabel)
        if (covers(slice, subject, topRank)) {
            return Adds(if (installed) "Already inside your $setTitle" else "Already inside $name", covered = installed)
        }
        val share = subject.adds[slice.zimName] ?: return null
        val your = if (installed) "your " else ""
        // Half rounds down: the shares are stored to three places, so 0.955 is a 95.499 % measured.
        val percent = "${ceil(share * 100 - 0.5).toInt().coerceIn(1, 100)}% new"
        if (subject.articles <= 0) return Adds("Adds to $your$name ($percent)", false)
        val count = String.format(Locale.ENGLISH, "%,d", roundCount((subject.articles * share).roundToLong()))
        return Adds("Adds about $count articles to $your$name ($percent)", false)
    }

    /** A count as a line says it: to the nearest 10 under 1,000, 100 under 10,000, else to three figures. */
    fun roundCount(n: Long): Long {
        if (n < 10) return n
        val step = when {
            n < 1_000 -> 10L
            n < 10_000 -> 100L
            else -> generateSequence(1L) { it * 10 }.first { n / it < 1_000 }
        }
        return (n + step / 2) / step * step
    }

    private fun defaultOf(flavours: List<PackEntry>, freeBytes: Long?): PackEntry {
        val pool = flavours.filter { it.featured }.ifEmpty { flavours }
        val fits = pool.filter { freeBytes == null || it.sizeBytes <= freeBytes }
        return fits.maxByOrNull { it.sizeBytes } ?: pool.minBy { it.sizeBytes }
    }

    /** The flavour with pictures names the pack, less "(no pictures)" or "(with pictures)" if a title carries it. */
    fun groupTitle(group: PackGroup): String {
        group.setId?.let { return setTitle(it, group.flavours) }
        val named = group.flavours.firstOrNull { flavourOf(it) == "maxi" } ?: group.default
        return named.title.replace(FLAVOUR_WORDS, "").trim()
    }

    private val FLAVOUR_WORDS = Regex("\\s*\\((no|with) pictures\\)\\s*$", RegexOption.IGNORE_CASE)

    /** The flavour smaller than [entry] in its [group] that comes closest to it; null when there's none. */
    fun smallerThan(group: PackGroup?, entry: PackEntry): PackEntry? =
        group?.flavours?.filter { it.sizeBytes < entry.sizeBytes }?.maxByOrNull { it.sizeBytes }

    // --- Folded families (Decision 10) ---

    /** A big family the Kiwix list shows as one row, "[label] · 243 [noun]", opening its own A–Z list. */
    data class Folded(val family: String, val label: String, val noun: String)

    /** The families folded away; a new row here folds another (Plan KB Decision 10). */
    val FOLDED = listOf(
        Folded("devdocs", "Developer docs", "packs"),
        Folded("stack_exchange", "Q&A sites", "packs"),
        Folded("maps", "Maps", "regions"),
    )

    fun folded(family: String): Folded? = FOLDED.firstOrNull { it.family == family }

    /** A folded family's row text: "Developer docs · 243 packs". */
    fun foldedText(folded: Folded, count: Int): String = "${folded.label} · $count ${folded.noun}"

    // --- Search (Decision 11) ---

    /**
     * Whether every word of [words] (folded, from [SearchText.words]) is somewhere in [group]: its titles, description,
     * ZIM name, category, family (and its label) or a language's name. Accents fold, so "encyclopedie" finds
     * "Encyclopédie" and the other way round.
     */
    fun matches(group: PackGroup, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val haystack = SearchText.fold(searchText(group))
        return words.all { it in haystack }
    }

    /** Whether every word is in the pack's own title: such a match ranks first. */
    fun titleMatches(group: PackGroup, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val title = SearchText.fold(group.flavours.joinToString(" ") { it.title } + " " + group.title)
        return words.all { it in title }
    }

    private fun searchText(group: PackGroup): String = buildList {
        add(group.title)
        for (entry in group.flavours) {
            add(entry.title)
            add(entry.description)
            add(entry.zimName)
            add(entry.category)
            add(entry.family)
            folded(entry.family)?.let { add(it.label) }
            entry.language.split(',').forEach { add(languageName(it)) }
        }
    }.joinToString(" ")

    // --- Plain words (Decision 9) ---

    /** Languages ISO 639-3 doesn't name the same way as Java's 639-1 table; the rest come from [Locale]. */
    private val LANGUAGE_NAMES = mapOf(
        "zho" to "Chinese", "fas" to "Persian", "bod" to "Tibetan", "nhe" to "Nahuatl", "ces" to "Czech",
        "deu" to "German", "fra" to "French", "nld" to "Dutch", "ell" to "Greek", "ron" to "Romanian", "slk" to "Slovak",
        "isl" to "Icelandic", "cym" to "Welsh", "mri" to "Maori", "msa" to "Malay", "sqi" to "Albanian", "hye" to "Armenian",
        "eus" to "Basque", "kat" to "Georgian", "mkd" to "Macedonian", "mya" to "Burmese",
    )

    /** One language code's English name: "eng" → "English"; the code itself when nothing names it. */
    fun languageName(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty()) return ""
        LANGUAGE_NAMES[c]?.let { return it }
        val name = Locale(c).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank() || name.equals(c, ignoreCase = true)) c else name
    }

    /** "English", "English, Russian", or with more than three "English, French, German and 8 more". */
    fun languages(codes: String): String {
        val names = codes.split(',').map(::languageName).filter { it.isNotEmpty() }.distinct()
        return if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " and ${names.size - 3} more"
    }

    /** "Updated March 2026" from a snapshot date ("2026-03-05"); the date as it is when it won't parse. */
    fun updated(snapshotDate: String): String = try {
        "Updated " + LocalDate.parse(snapshotDate.take(10)).format(MONTH_YEAR)
    } catch (_: DateTimeParseException) {
        if (snapshotDate.isBlank()) "" else "Updated $snapshotDate"
    }

    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    private val CC = Regex("^(CC BY(?:-(?:SA|NC|ND))*)(?:\\s+[\\d.]+)?$", RegexOption.IGNORE_CASE)

    /**
     * A licence's short name for a row: "CC BY-SA" for "CC BY-SA 4.0", "Open licences" for the mixed and per-project
     * ones, "Public domain", "Licence not stated"; the full string goes in the row's details.
     */
    fun licence(full: String): String {
        val text = full.trim()
        val lower = text.lowercase()
        CC.matchEntire(text)?.let { return it.groupValues[1].uppercase() }
        return when {
            text.isEmpty() || lower.startsWith("not stated") -> "Licence not stated"
            lower.startsWith("public domain") || lower.startsWith("public information") -> "Public domain"
            lower.startsWith("open licen") || lower.startsWith("mixed open licen") -> "Open licences"
            lower.startsWith("cc0") -> "CC0"
            lower.startsWith("odbl") -> "ODbL"
            lower.startsWith("gfdl") -> "GFDL"
            lower.startsWith("cc by") -> "CC licences"
            text.length <= 24 -> text
            else -> "See licence"
        }
    }

    /** A pack row's details: "English · Updated March 2026 · CC BY-SA", and Kiwix's credit where the licence asks. */
    fun details(entry: PackEntry): String = listOf(
        languages(entry.language), updated(entry.snapshotDate),
        licence(entry.license).let { if (entry.nonCommercial) "$it · provided by Kiwix" else it },
    ).filter { it.isNotBlank() }.joinToString(" · ")
}
