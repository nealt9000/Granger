package app.granger.sources

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.granger.R
import app.granger.UiText
import app.granger.core.catalog.PackEntry
import app.granger.core.library.SearchText
import java.text.NumberFormat
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

/** The Kiwix list's packs, grouped and in plain words (Plan KB Decisions 8 and 9). Pure: its words are [UiText]. */
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

    /** What a flavour is called on a row: With pictures, No pictures, Mini; Standard for an unflavoured ZIM. */
    fun flavourLabel(flavour: String): UiText = UiText.Res(
        when (flavour) {
            "maxi" -> R.string.sources_flavour_with_pictures
            "nopic" -> R.string.sources_flavour_no_pictures
            "mini" -> R.string.sources_flavour_mini
            else -> R.string.sources_flavour_standard
        },
    )

    /** [entry]'s flavour as a row says it. */
    fun flavourLabel(entry: PackEntry): UiText = flavourLabel(flavourOf(entry))

    /**
     * Each of [group]'s flavours as its choice says it, by id, no two the same (Plan KB walkthrough 2): the flavour, and
     * where two share one, what else differs: the month updated, the language, the title, else the id.
     */
    fun flavourLabels(group: PackGroup): Map<String, UiText> {
        val labels = group.flavours.associate { it.id to flavourLabel(it) }.toMutableMap()
        val distinctions = listOf<(PackEntry) -> UiText>(
            { updated(it.snapshotDate) ?: UiText.Raw("") },
            { e -> UiText.Raw(e.language.split(',').joinToString(", ") { languageName(it) }) },
            { UiText.Raw(it.title) },
            { UiText.Raw(it.id) },
        )
        for ((label, same) in group.flavours.groupBy { labels.getValue(it.id) }) {
            if (same.size < 2) continue
            val by = distinctions.firstOrNull { d -> same.map(d).distinct().size == same.size } ?: distinctions.last()
            for (entry in same) labels[entry.id] = dotted(label, by(entry))
        }
        return labels
    }

    /** Two parts of a line with a dot between: "No pictures · March 2026". */
    fun dotted(a: Any, b: Any): UiText = UiText.Res(R.string.sources_dotted, a, b)

    /** [parts] in a line, a dot between each; blank parts left out. */
    fun dotted(parts: List<UiText>): UiText {
        val shown = parts.filterNot { it is UiText.Raw && it.text.isBlank() }
        return shown.drop(1).fold(shown.firstOrNull() ?: UiText.Raw("")) { line, part -> dotted(line, part) }
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

    /**
     * The English sets' lines as resources, so the Spanish app has them in Spanish (the catalog writes no Spanish copy
     * for an English set). A set this doesn't know takes its catalog copy (Plan L3).
     */
    private val SET_TEXT = mapOf(
        "wikipedia_en" to ("Wikipedia" to R.string.sources_set_wikipedia),
        "wikivoyage_en" to ("Wikivoyage" to R.string.sources_set_wikivoyage),
    )

    /** The app's language: catalog copy is picked by it (Plan L3), as [languageName] names languages in it. */
    private fun appLanguage(): String = Locale.getDefault().language

    /** [entry]'s copy in the app's language, when the catalog has one. */
    private fun copy(entry: PackEntry): app.granger.core.catalog.Copy? = entry.i18n[app.granger.core.catalog.Languages.normalise(appLanguage())]

    /**
     * A set's name (Plan L3): the catalog's copy in the app's language ("Wikipedia in Spanish" for an English reader),
     * else its own name ("Wikipedia en español"), else (older catalogs) the English sets' names, else its slices' title
     * before the colon.
     */
    fun setTitle(setId: String, entries: List<PackEntry>): String =
        entries.firstNotNullOfOrNull { copy(it)?.setTitle?.ifBlank { null } }
            ?: entries.firstNotNullOfOrNull { it.setTitle.ifBlank { null } }
            ?: SET_TEXT[setId]?.first ?: entries.firstOrNull()?.title?.substringBefore(": ").orEmpty()

    /** A set's line: the catalog's copy in the app's language, else the English sets' resources, else the catalog's own. */
    fun setDescription(setId: String, entries: List<PackEntry>): UiText =
        entries.firstNotNullOfOrNull { copy(it)?.setDescription?.ifBlank { null } }?.let { UiText.Raw(it) }
            ?: SET_TEXT[setId]?.second?.let { UiText.Res(it) }
            ?: entries.firstNotNullOfOrNull { it.setDescription.ifBlank { null } }?.let { UiText.Raw(it) }
            ?: UiText.Raw(entries.maxByOrNull { it.setRank }?.description.orEmpty())

    /** A slice's label in the app's language: "Best 50,000 articles" for an English reader of Spanish Top. */
    fun label(entry: PackEntry): String = copy(entry)?.setLabel?.ifBlank { null } ?: entry.setLabel

    /** An item's title in the app's language: a pack's curated copy (Plan L3), else the catalog's own. */
    fun title(item: app.granger.core.catalog.CatalogItem): String = (item as? PackEntry)?.title(appLanguage()) ?: item.title

    /** One slice of a set's row: its ZIM name, rank, label and flavours (with pictures first). */
    data class Slice(val zimName: String, val rank: Int, val label: String, val entries: List<PackEntry>)

    /** [group]'s slices, smallest first. */
    fun slices(group: PackGroup): List<Slice> = group.flavours.groupBy { it.zimName }.values
        .map { e -> Slice(e.first().zimName, e.first().setRank, label(e.first()), e.sortedBy(::flavourRank)) }
        .sortedBy { it.rank }

    /** The pictures choice a slice entry is: its flavour, an unflavoured slice (the 100) counting as with pictures. */
    fun picturesOf(entry: PackEntry): String = flavourOf(entry).ifBlank { "maxi" }

    /** [slice]'s entry for the [pictures] choice, else its first (it doesn't offer that choice). */
    fun entryFor(slice: Slice, pictures: String): PackEntry =
        slice.entries.firstOrNull { picturesOf(it) == pictures } ?: slice.entries.first()

    /** A slice's name in a sentence: "Best 50,000", "Top 1 million", "Everything"; "Los 50.000 mejores" in Spanish. */
    fun shortLabel(label: String): String = label.removeSuffix(" articles").removeSuffix(" artículos")

    /** [entry]'s slice name in a sentence, in the app's language. */
    fun shortLabel(entry: PackEntry): String = shortLabel(label(entry))

    /** Whether [slice] (of rank [slice].setRank, the set's top rank [topRank]) holds [subject]'s articles. */
    fun covers(slice: PackEntry, subject: PackEntry, topRank: Int): Boolean =
        slice.setRank >= topRank || (subject.adds[slice.zimName]?.let { it < app.granger.core.catalog.COVERED_SHARE } ?: false)

    /** What a subject's row says about [slice] (Plan WF Decision 4): its [text], and whether it is [covered] already. */
    data class Adds(val text: UiText, val covered: Boolean)

    /**
     * "Adds about 69,600 articles to your Best 50,000 (95% new)" against the [installed] slice, or "… to Top 1 million …"
     * against a chosen one; "Already inside your Wikipedia" when the slice covers it. Null when the catalog doesn't say.
     */
    fun adds(subject: PackEntry, slice: PackEntry, topRank: Int, setTitle: String, installed: Boolean): Adds? {
        val name = shortLabel(slice)
        if (covers(slice, subject, topRank)) {
            return Adds(
                if (installed) UiText.Res(R.string.sources_adds_inside_your, setTitle) else UiText.Res(R.string.sources_adds_inside, name),
                covered = installed,
            )
        }
        val share = subject.adds[slice.zimName] ?: return null
        // Half rounds down: the shares are stored to three places, so 0.955 is a 95.499 % measured.
        val percent = ceil(share * 100 - 0.5).toInt().coerceIn(1, 100)
        if (subject.articles <= 0) {
            return Adds(UiText.Res(if (installed) R.string.sources_adds_to_your else R.string.sources_adds_to, name, percent), false)
        }
        val n = roundCount((subject.articles * share).roundToLong())
        val count = NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)
        val plural = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Adds(
            UiText.Plural(if (installed) R.plurals.sources_adds_about_your else R.plurals.sources_adds_about, plural, count, name, percent),
            false,
        )
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
        return title(named).replace(FLAVOUR_WORDS, "").trim()
    }

    private val FLAVOUR_WORDS = Regex("\\s*\\((no|with) pictures\\)\\s*$", RegexOption.IGNORE_CASE)

    /** The flavour smaller than [entry] in its [group] that comes closest to it; null when there's none. */
    fun smallerThan(group: PackGroup?, entry: PackEntry): PackEntry? =
        group?.flavours?.filter { it.sizeBytes < entry.sizeBytes }?.maxByOrNull { it.sizeBytes }

    // --- Folded families (Decision 10) ---

    /**
     * A big family the Kiwix list shows as one row, "[label] · 243 [noun]", opening its own A–Z list. [words] are what a
     * search finds it by, in English like the catalog's own words (L3).
     */
    data class Folded(val family: String, @StringRes val label: Int, @PluralsRes val noun: Int, val words: String)

    /** The families folded away; a new row here folds another (Plan KB Decision 10). */
    val FOLDED = listOf(
        Folded("devdocs", R.string.sources_family_devdocs, R.plurals.sources_packs, "Developer docs"),
        Folded("stack_exchange", R.string.sources_family_stack_exchange, R.plurals.sources_packs, "Q&A sites"),
        Folded("maps", R.string.sources_family_maps, R.plurals.sources_regions, "Maps"),
    )

    fun folded(family: String): Folded? = FOLDED.firstOrNull { it.family == family }

    /** A folded family's count: "243 packs". */
    fun foldedCount(folded: Folded, count: Int): UiText = UiText.Plural(folded.noun, count, count)

    /** A folded family's row text: "Developer docs · 243 packs". */
    fun foldedText(folded: Folded, count: Int): UiText = dotted(UiText.Res(folded.label), foldedCount(folded, count))

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
            folded(entry.family)?.let { add(it.words) }
            entry.language.split(',').forEach { add(languageName(it)) }
        }
    }.joinToString(" ")

    // --- Plain words (Decision 9) ---

    /** ISO 639-3 codes [Locale] names only by another code (its 639-1 one, or 639-2's for Nahuatl). */
    private val LANGUAGE_CODES = mapOf(
        "zho" to "zh", "fas" to "fa", "bod" to "bo", "nhe" to "nah", "ces" to "cs",
        "deu" to "de", "fra" to "fr", "nld" to "nl", "ell" to "el", "ron" to "ro", "slk" to "sk",
        "isl" to "is", "cym" to "cy", "mri" to "mi", "msa" to "ms", "sqi" to "sq", "hye" to "hy",
        "eus" to "eu", "kat" to "ka", "mkd" to "mk", "mya" to "my",
    )

    /** One language code's name in the phone's language: "eng" → "English"; the code itself when nothing names it. */
    fun languageName(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty()) return ""
        val name = Locale(LANGUAGE_CODES[c] ?: c).getDisplayLanguage(Locale.getDefault())
        return if (name.isBlank() || name.equals(c, ignoreCase = true)) c else name
    }

    /** "English", "English, Russian", or with more than three "English, French, German and 8 more". */
    fun languages(codes: String): UiText {
        val names = codes.split(',').map(::languageName).filter { it.isNotEmpty() }.distinct()
        return if (names.size <= 3) UiText.Raw(names.joinToString(", "))
        else UiText.Plural(R.plurals.sources_languages_more, names.size - 3, names.take(3).joinToString(", "), names.size - 3)
    }

    /** "Updated March 2026" from a snapshot date ("2026-03-05"); the date as it is when it won't parse; null when blank. */
    fun updated(snapshotDate: String): UiText? = try {
        UiText.Res(R.string.sources_updated, LocalDate.parse(snapshotDate.take(10)).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())))
    } catch (_: DateTimeParseException) {
        if (snapshotDate.isBlank()) null else UiText.Res(R.string.sources_updated, snapshotDate)
    }

    private val CC = Regex("^(CC BY(?:-(?:SA|NC|ND))*)(?:\\s+[\\d.]+)?$", RegexOption.IGNORE_CASE)

    /**
     * A licence's short name for a row: "CC BY-SA" for "CC BY-SA 4.0", "Open licences" for the mixed and per-project
     * ones, "Public domain", "Licence not stated"; the full string goes in the row's details.
     */
    fun licence(full: String): UiText {
        val text = full.trim()
        val lower = text.lowercase()
        // The licences' own names (CC BY-SA, CC0, ODbL, GFDL) are the same in every language.
        CC.matchEntire(text)?.let { return UiText.Raw(it.groupValues[1].uppercase()) }
        return when {
            text.isEmpty() || lower.startsWith("not stated") -> UiText.Res(R.string.sources_licence_not_stated)
            lower.startsWith("public domain") || lower.startsWith("public information") -> UiText.Res(R.string.sources_licence_public_domain)
            lower.startsWith("open licen") || lower.startsWith("mixed open licen") -> UiText.Res(R.string.sources_licence_open)
            lower.startsWith("cc0") -> UiText.Raw("CC0")
            lower.startsWith("odbl") -> UiText.Raw("ODbL")
            lower.startsWith("gfdl") -> UiText.Raw("GFDL")
            lower.startsWith("cc by") -> UiText.Res(R.string.sources_licence_cc)
            text.length <= 24 -> UiText.Raw(text)
            else -> UiText.Res(R.string.sources_licence_see)
        }
    }

    /** A pack row's details: "English · Updated March 2026 · CC BY-SA", and Kiwix's credit where the licence asks. */
    /**
     * The pack on Kiwix's online library, to look through in the phone's browser before a multi-GB download (Neal,
     * 2026-11-02): library.kiwix.org serves each ZIM at /content/<file name without .zim>/. Null for a pack Kiwix
     * doesn't host.
     */
    fun previewUrl(entry: PackEntry): String? {
        val url = entry.urls.firstOrNull { it.startsWith(KIWIX_DOWNLOADS) } ?: return null
        val name = url.substringAfterLast('/').removeSuffix(".zim").takeIf { it.isNotBlank() && '?' !in it } ?: return null
        return "$KIWIX_LIBRARY$name/"
    }

    private const val KIWIX_DOWNLOADS = "https://download.kiwix.org/zim/"
    private const val KIWIX_LIBRARY = "https://library.kiwix.org/content/"

    fun details(entry: PackEntry): UiText = dotted(
        listOfNotNull(
            languages(entry.language), updated(entry.snapshotDate),
            licence(entry.license).let { if (entry.nonCommercial) UiText.Res(R.string.sources_licence_kiwix, it) else it },
        ),
    )
}
