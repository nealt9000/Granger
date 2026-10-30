package app.granger.sources

import app.granger.core.catalog.PackEntry
import app.granger.core.library.SearchText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * One pack of the Kiwix list, whatever its flavours (Plan KB Decision 8): every catalog entry with the same ZIM name,
 * [flavours] with pictures first, and the one shown by [default].
 */
data class PackGroup(val zimName: String, val flavours: List<PackEntry>, val default: PackEntry) {
    /** The pack's name without a flavour's own words: "WikiMed", not "WikiMed (no pictures)". */
    val title: String get() = KiwixList.groupTitle(this)

    val featured: Boolean get() = flavours.any { it.featured }

    fun flavour(id: String): PackEntry? = flavours.firstOrNull { it.id == id }
}

/** The Kiwix list's packs, grouped and in plain words (Plan KB Decisions 8 and 9). Pure. */
object KiwixList {
    /** Flavours in the order they're offered: with pictures, without, mini, then an unflavoured ZIM. */
    private val FLAVOUR_ORDER = listOf("maxi", "nopic", "mini", "")

    /** A Get over this asks first, with the free space and a smaller flavour (Plan KB Decision 9): 10 GB. */
    const val SIZE_CHECK_BYTES = 10L * 1000 * 1000 * 1000

    /** What a flavour is called on a row: "With pictures", "No pictures", "Mini"; "Standard" for an unflavoured ZIM. */
    fun flavourLabel(flavour: String): String = when (flavour) {
        "maxi" -> "With pictures"
        "nopic" -> "No pictures"
        "mini" -> "Mini"
        else -> "Standard"
    }

    /**
     * [packs] grouped by ZIM name, in the order each name first appears. A group's default is its featured flavour
     * (the largest featured that fits in [freeBytes], else the smallest featured), else the largest flavour that fits,
     * else the smallest; with [freeBytes] null every flavour fits.
     */
    fun group(packs: List<PackEntry>, freeBytes: Long? = null): List<PackGroup> =
        packs.groupBy { it.zimName.ifBlank { it.id } }.map { (name, entries) ->
            val flavours = entries.sortedBy { FLAVOUR_ORDER.indexOf(it.flavour).let { i -> if (i < 0) FLAVOUR_ORDER.size else i } }
            PackGroup(name, flavours, defaultOf(flavours, freeBytes))
        }

    private fun defaultOf(flavours: List<PackEntry>, freeBytes: Long?): PackEntry {
        val pool = flavours.filter { it.featured }.ifEmpty { flavours }
        val fits = pool.filter { freeBytes == null || it.sizeBytes <= freeBytes }
        return fits.maxByOrNull { it.sizeBytes } ?: pool.minBy { it.sizeBytes }
    }

    /** The flavour with pictures names the pack, less "(no pictures)" or "(with pictures)" if a title carries it. */
    fun groupTitle(group: PackGroup): String {
        val named = group.flavours.firstOrNull { it.flavour == "maxi" } ?: group.default
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
