package app.granger.onboarding

import app.granger.R
import app.granger.UiText
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ENGLISH
import app.granger.core.catalog.Languages
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.UseCase
import app.granger.core.catalog.VoiceEntry
import app.granger.core.catalog.downloadBytes
import app.granger.core.catalog.vectors
import app.granger.research.Librarian

/**
 * One row the user can untick: a pack, or a collection's books folded together ([count] > 1). [installed] rows are on
 * the phone already and never count toward a total.
 */
data class Line(
    val id: String,
    /** A catalog title ([UiText.Raw]), or the app's own name for the line (Ask, the voice, the Classics). */
    val title: UiText,
    val items: List<CatalogItem>,
    val installed: Boolean,
) {
    val count: Int get() = items.size
    val bytes: Long get() = items.sumOf { it.downloadBytes }
}

/** Efficient or Comprehensive (spec §6, step 3), built from the generated preset of that name. */
data class StartChoice(val id: String, val title: String, val blurb: UiText, val lines: List<Line>) {
    /** What downloading it would add, with [unticked] lines and installed ones left out. */
    fun bytes(unticked: Set<String> = emptySet()): Long = chosen(unticked).sumOf { it.bytes }

    fun chosen(unticked: Set<String> = emptySet()): List<Line> = lines.filter { !it.installed && it.id !in unticked }
}

/**
 * A map pack the region picker offers. A [place] inside a larger pack with no pack of its own (Puerto Rico in Central
 * America) is offered under its own name and chooses the pack.
 */
data class Region(val pack: PackEntry, val installed: Boolean, val place: String? = null) {
    /** What the row says: the place, or the pack's title. */
    val title: String get() = place ?: pack.title
    val bytes: Long get() = pack.sizeBytes
}

/**
 * Countries first, then continents and multi-country areas (Plan H3, Decision 3); [places] are the covered places,
 * shown only when a search names one.
 */
data class Regions(val countries: List<Region>, val larger: List<Region>, val places: List<Region> = emptyList()) {
    fun matching(query: String): Regions {
        val q = query.trim()
        if (q.isEmpty()) return copy(places = emptyList())
        fun List<Region>.hit() = filter { it.title.contains(q, ignoreCase = true) }
        return Regions(countries.hit() + places.hit(), larger.hit())
    }
}

/**
 * What a use-case chip adds beyond what is already chosen or installed (Decision 4); [title] and [blurb] are the
 * catalog's copy in the app's language (Plan L3).
 */
data class Addition(val useCase: UseCase, val lines: List<Line>, val title: String = useCase.title, val blurb: String = useCase.blurb) {
    val bytes: Long get() = lines.sumOf { it.bytes }
    val packs: Int get() = lines.sumOf { it.count }
}

data class ReviewGroup(val title: UiText, val lines: List<Line>)

/** The review step: every item once, the total against the chosen volume's free space. */
data class Review(
    val groups: List<ReviewGroup>,
    val freeBytes: Long,
    /** What was left out because a bigger pack in the review holds it (Plan WF Decision 5). */
    val notes: List<UiText> = emptyList(),
) {
    val lines: List<Line> get() = groups.flatMap { it.lines }
    val items: List<CatalogItem> get() = lines.flatMap { it.items }
    val bytes: Long get() = items.sumOf { it.downloadBytes }
    /** The part of [bytes] that is Ask's search indexes (`.gvec`), fetched after their packs. */
    val indexBytes: Long get() = items.sumOf { it.vectors?.sizeBytes ?: 0L }
    val fits: Boolean get() = bytes <= freeBytes
    val isEmpty: Boolean get() = items.isEmpty()
}

/** The onboarding flow's data, computed from the cached catalog and what is installed. Pure. */
object OnboardingModel {
    const val STARTER = "starter"
    const val EFFICIENT = "efficient"

    /** Pick a start's choice before the user picks: the smallest start offered ([startChoices]' first). */
    const val DEFAULT_START = "default"
    const val COMPREHENSIVE = "comprehensive"
    const val ASK = "ask"
    const val VOICE = "voice"
    const val VOICE_ID = "vits-piper-en_GB-granger_owl-medium"

    /**
     * The start choices, smallest first: Starter where the language has one (Neal, 2026-11-02: the ~20 GB Efficient
     * start is a middle tier, so Starter is the default), then the generated Efficient and Comprehensive presets; a
     * catalog without them (older) gives an empty list. Ask's models are left out of a start (the Starter preset lists
     * them), since Ask is its own line under the choices. [saveSpace] swaps each pack for its no-pictures flavour where the catalog has one (Neal, 2026-10-28: pictures are
     * the default, no-pictures the space-saving fallback).
     */
    fun startChoices(
        catalog: Catalog,
        installed: Set<String>,
        saveSpace: Boolean = false,
        language: String = ENGLISH,
        appLanguage: String = ENGLISH,
    ): List<StartChoice> {
        val blurbs = mapOf(
            STARTER to UiText.Res(R.string.onboarding_blurb_starter),
            EFFICIENT to UiText.Res(R.string.onboarding_blurb_efficient),
            COMPREHENSIVE to UiText.Res(R.string.onboarding_blurb_comprehensive),
        )
        // The main knowledge language's tier presets (Plan L3): `efficient-es` is still the Efficient choice.
        val code = Languages.normalise(language)
        val starterId = if (code == ENGLISH) STARTER else "$STARTER-$code"
        val starter = catalog.presets.firstOrNull { it.id == starterId && it.language == code }
        return (listOfNotNull(starter) + catalog.tierPresets(language)).mapNotNull { preset ->
            val id = (listOf(STARTER) + Catalog.TIER_PRESETS).firstOrNull { preset.id == it || preset.id.startsWith("$it-") } ?: return@mapNotNull null
            val items = preset.items.mapNotNull { catalog.item(it) }.filter { it !is ModelEntry }.map { if (saveSpace) withoutPictures(catalog, it) else it }.distinctBy { it.id }
            StartChoice(id, preset.title(appLanguage), blurbs.getValue(id), lines(items, installed))
        }
    }

    /** Pick a start's line for a second knowledge language (Plan L3): the second language's encyclopedia. */
    const val SECOND = "second"

    /**
     * A second knowledge [language]'s encyclopedia, its `reference` essential, as one line under the start choices
     * ("Encyclopedia in Spanish"); its own Review group, which the user can untick. Save space swaps flavours as the
     * starts do. Null when the language has none, or it's on the phone already.
     */
    fun secondLine(catalog: Catalog, language: String, installed: Set<String>, saveSpace: Boolean = false): Line? {
        val reference = catalog.readiness?.domains?.firstOrNull { it.id == REFERENCE } ?: return null
        val items = reference.essential(language).flatMap { resolve(catalog, it) }
            .map { if (saveSpace) withoutPictures(catalog, it) else it }.distinctBy { it.id }.filter { it.id !in installed }
        if (items.isEmpty()) return null
        return Line(SECOND, UiText.Res(R.string.onboarding_second_encyclopedia, app.granger.sources.KiwixList.languageName(language)), items, installed = false)
    }

    /** The readiness domain whose essential tier is a language's encyclopedia. */
    private const val REFERENCE = "reference"

    /**
     * Ask's models as one line (Plan U3a Decision 2): the answering model, plus the embedder when [dense] retrieval is on,
     * less what's installed. Null on a phone that can't run Ask, in a catalog with no answering model (release builds
     * today), or when everything is on the phone already.
     */
    fun askLine(catalog: Catalog, tier: DeviceTier, dense: Boolean, installed: Set<String>): Line? {
        if (tier < DeviceTier.ASK) return null
        val items = Librarian.models(catalog, dense).filter { it.id !in installed }
        return if (items.isEmpty()) null else Line(ASK, UiText.Res(R.string.onboarding_ask_line), items, installed = false)
    }

    /**
     * The voice offered in Pick a start (Plan U3a, Neal's walkthrough): the catalog's Granger voice of [VOICE_ID], else
     * its first, as one "Read aloud" line. Null with no voices in the catalog or when any catalog voice is installed.
     */
    fun voiceLine(catalog: Catalog, installed: Set<String>): Line? {
        if (catalog.voices.any { it.id in installed }) return null
        val voice = catalog.voices.firstOrNull { it.id == VOICE_ID } ?: catalog.voices.firstOrNull() ?: return null
        return Line(VOICE, UiText.Res(R.string.onboarding_voice_line), listOf(voice), installed = false)
    }

    /** [item]'s no-pictures flavour (`…_nopic`, same ZIM), or [item] itself. */
    fun withoutPictures(catalog: Catalog, item: CatalogItem): CatalogItem {
        if (item !is PackEntry || item.zimName.isBlank() || item.id.endsWith("_nopic")) return item
        return catalog.packs.firstOrNull { it.zimName == item.zimName && it.id.endsWith("_nopic") } ?: item
    }

    /** The maps family split by [app.granger.core.catalog.Onboarding.larger], each list by title, plus the covered places. */
    fun regions(catalog: Catalog, installed: Set<String>): Regions {
        val larger = catalog.onboarding?.larger.orEmpty().toSet()
        val maps = catalog.packs.filter { it.family == "maps" }.map { Region(it, it.id in installed) }.sortedBy { it.title.lowercase() }
        val byId = maps.associateBy { it.pack.id }
        val places = catalog.onboarding?.covered.orEmpty().flatMap { (packId, names) ->
            val region = byId[packId] ?: return@flatMap emptyList()
            names.map { region.copy(place = it) }
        }.sortedBy { it.title.lowercase() }
        return Regions(countries = maps.filter { it.pack.id !in larger }, larger = maps.filter { it.pack.id in larger }, places = places)
    }

    /**
     * The map of where the phone is (Plan U3a Decision 4): the country pack titled with the phone's country in English,
     * else the one [REGION_ALIASES] names for it. Null for an unknown code or a country with no pack of its own.
     */
    fun suggestedRegion(regions: Regions, countryCode: String): Region? {
        val code = countryCode.uppercase()
        if (code.isBlank()) return null
        // English on purpose, whatever the phone's language: this matches the catalog's English map titles, not UI (L3).
        val name = java.util.Locale("", code).getDisplayCountry(java.util.Locale.ENGLISH)
        val titles = listOfNotNull(name.takeIf { it.isNotBlank() && it != code }, REGION_ALIASES[code])
        return titles.firstNotNullOfOrNull { t -> regions.countries.firstOrNull { it.place == null && it.pack.title.equals(t, ignoreCase = true) } }
    }

    /**
     * Country codes whose map pack the catalog titles differently from the phone's English name for the country (checked
     * against the catalog's 193 maps titles, 2026-10-28), or that share a pack with a neighbour.
     */
    /** A suggested map larger than this is offered, not preselected: the US map alone is 17 GB. */
    const val PRESELECT_MAX_BYTES = 2_000_000_000L

    val REGION_ALIASES = mapOf(
        "US" to "United States of America", "RU" to "Russian Federation", "CZ" to "Czech Republic", "TR" to "Turkey",
        "CD" to "Dem. Rep. of Congo (Kinshasa)", "CG" to "Rep. of Congo (Brazzaville)", "CI" to "Ivory Coast",
        "BA" to "Bosnia-Herzegovina", "MK" to "Macedonia", "MM" to "Myanmar (a.k.a. Burma)", "TL" to "East Timor",
        "KM" to "Comores", "ST" to "Sao Tome and Principe", "SZ" to "Swaziland", "WF" to "Wallis et Futuna", "XK" to "Kosovo",
        "GG" to "Guernsey and Jersey", "JE" to "Guernsey and Jersey", "HT" to "Haiti and Dominican Republic", "DO" to "Haiti and Dominican Republic",
        "IL" to "Israel and Palestine", "PS" to "Israel and Palestine", "SN" to "Senegal and Gambia", "GM" to "Senegal and Gambia",
        "MY" to "Malaysia, Singapore and Brunei", "SG" to "Malaysia, Singapore and Brunei", "BN" to "Malaysia, Singapore and Brunei",
    )

    /**
     * Each use case's lines beyond [chosen] item ids and [installed] ones; a use case left with nothing is kept with no
     * lines. Plan L3: its items in each knowledge language of [languages] (main first); a use case with no items in any of
     * them isn't offered. Its title and blurb are in [appLanguage].
     */
    fun additions(
        catalog: Catalog,
        installed: Set<String>,
        chosen: Set<String>,
        languages: List<String> = listOf(ENGLISH),
        appLanguage: String = ENGLISH,
    ): List<Addition> =
        catalog.onboarding?.useCases.orEmpty().mapNotNull { useCase ->
            val tokens = languages.flatMap { useCase.items(it) }.distinct()
            if (tokens.isEmpty() && useCase.items.isNotEmpty()) return@mapNotNull null
            val items = tokens.flatMap { resolve(catalog, it) }.distinctBy { it.id }.filter { it.id !in chosen }
            Addition(useCase, lines(items, installed).filterNot { it.installed }, useCase.title(appLanguage), useCase.blurb(appLanguage))
        }

    /**
     * The review: the start's ticked lines, the region, then the use cases' lines, each item once, nothing installed.
     */
    fun review(
        start: StartChoice?,
        unticked: Set<String>,
        region: Region?,
        additions: List<Addition>,
        freeBytes: Long,
        ask: Line? = null,
        voice: Line? = null,
        containment: app.granger.core.catalog.Containment? = null,
        second: Line? = null,
    ): Review {
        val seen = HashSet<String>()
        fun List<Line>.fresh(): List<Line> = mapNotNull { line ->
            val items = line.items.filter { seen.add(it.id) }
            if (items.isEmpty()) null else line.copy(items = items)
        }
        val groups = buildList {
            start?.let { s ->
                // The preset's name only while it is the preset: trimmed, it's the user's own (Plan U3a Decision 5).
                val whole = s.lines.none { !it.installed && it.id in unticked }
                s.chosen(unticked).fresh().takeIf { it.isNotEmpty() }?.let { add(ReviewGroup(if (whole) UiText.Raw(s.title) else UiText.Res(R.string.onboarding_your_picks), it)) }
            }
            // Under its own heading the line is "The librarian", not "Ask (Preview)" twice.
            voice?.let { v -> listOf(v.copy(title = UiText.Raw(v.items.single().title))).fresh().takeIf { it.isNotEmpty() }?.let { add(ReviewGroup(v.title, it)) } }
            ask?.let { listOf(it.copy(title = UiText.Res(R.string.onboarding_the_librarian))).fresh().takeIf { l -> l.isNotEmpty() }?.let { l -> add(ReviewGroup(it.title, l)) } }
            // A second language's encyclopedia (Plan L3): its own group, each pack under its own title.
            second?.let { line ->
                line.items.map { Line(it.id, UiText.Raw(app.granger.sources.KiwixList.title(it)), listOf(it), installed = false) }.fresh().takeIf { it.isNotEmpty() }
                    ?.let { add(ReviewGroup(line.title, it)) }
            }
            region?.takeUnless { it.installed }?.let { r ->
                listOf(Line(r.pack.id, UiText.Raw(r.title), listOf(r.pack), installed = false)).fresh().takeIf { it.isNotEmpty() }
                    ?.let { add(ReviewGroup(UiText.Res(R.string.onboarding_your_region), it)) }
            }
            val useCaseLines = additions.flatMap { it.lines }.fresh()
            if (useCaseLines.isNotEmpty()) add(ReviewGroup(UiText.Res(R.string.onboarding_uses), useCaseLines))
        }
        return if (containment == null) Review(groups, freeBytes) else held(groups, freeBytes, containment)
    }

    /**
     * [groups] less every pack another pack in them holds (Plan WF Decision 5): of two slices of one set only the
     * biggest is queued, and the review says so.
     */
    private fun held(groups: List<ReviewGroup>, freeBytes: Long, containment: app.granger.core.catalog.Containment): Review {
        val packs = groups.flatMap { g -> g.lines.flatMap { it.items } }.filterIsInstance<PackEntry>().filter { it.zimName.isNotBlank() }
        val byZim = packs.associateBy { it.zimName }
        val dropped = LinkedHashMap<String, PackEntry>() // held ZIM name -> the review's pack that holds it
        for (pack in packs) {
            val holder = containment.containers(pack.zimName).mapNotNull { byZim[it] }.maxByOrNull { it.setRank } ?: continue
            dropped.putIfAbsent(pack.zimName, holder)
        }
        if (dropped.isEmpty()) return Review(groups, freeBytes)
        val kept = groups.mapNotNull { g ->
            val lines = g.lines.mapNotNull { line ->
                val items = line.items.filterNot { it is PackEntry && it.zimName in dropped }
                if (items.isEmpty()) null else line.copy(items = items)
            }
            if (lines.isEmpty()) null else g.copy(lines = lines)
        }
        val notes = dropped.map { (zim, holder) -> heldText(holder, packs.first { it.zimName == zim }) }.distinct()
        return Review(kept, freeBytes, notes)
    }

    /** "Wikipedia Top 1 million covers Best 50,000, so only Top 1 million is queued." */
    fun heldText(holder: PackEntry, held: PackEntry): UiText =
        if (holder.isSlice && held.isSlice && holder.setId == held.setId) {
            val big = app.granger.sources.KiwixList.shortLabel(holder)
            val set = app.granger.sources.KiwixList.setTitle(holder.setId, listOf(holder))
            UiText.Res(R.string.onboarding_held_slice, set, big, app.granger.sources.KiwixList.shortLabel(held))
        } else {
            UiText.Res(R.string.onboarding_held, app.granger.sources.KiwixList.title(holder), app.granger.sources.KiwixList.title(held))
        }

    /** The Granger Classics' collection id. */
    const val CLASSICS = app.granger.core.library.Filing.GRANGER_CLASSICS

    /** Granger's book collections, one per knowledge language (Plan L3): Granger Classics and Clásicos Granger. */
    val GRANGER_COLLECTIONS: Set<String> = app.granger.core.library.Filing.GRANGER_COLLECTIONS.values.toSet()

    /** Small packs land early (Plan DL Decision 5): 200 MB or less. */
    const val SMALL_PACK_BYTES = 200L * 1000 * 1000

    /** A region's map lands early when it's 2 GB or less; a bigger one waits with the rest. */
    const val QUICK_MAP_BYTES = 2L * 1000 * 1000 * 1000

    /**
     * The order setup downloads [items] in (Plan DL Decision 5, quick wins first): the voice; either Granger collection
     * (Granger Classics, Clásicos Granger: Plan L3); small
     * packs in readiness-domain order ([domains]); the region's map ([regionPackId]) if it isn't big; everything else
     * smallest first (so Wikipedia Top and big maps come last among the packs); then Ask's models, the chat model
     * before the embedder. The review keeps its own grouping; only the queue takes this order.
     */
    fun downloadOrder(items: List<CatalogItem>, domains: List<String>, regionPackId: String?): List<CatalogItem> {
        fun tier(item: CatalogItem): Int = when {
            item is VoiceEntry -> 0
            item is BookEntry && item.collection in GRANGER_COLLECTIONS -> 1
            item is PackEntry && item.id == regionPackId -> if (item.sizeBytes <= QUICK_MAP_BYTES) 3 else 4
            item is PackEntry && item.sizeBytes <= SMALL_PACK_BYTES -> 2
            item is ModelEntry -> 5
            else -> 4
        }
        fun domainRank(item: CatalogItem) = (item as? PackEntry)?.domain?.let { d -> domains.indexOf(d).takeIf { it >= 0 } } ?: domains.size
        val order = compareBy<Pair<Int, CatalogItem>> { tier(it.second) }
            .thenBy { (_, item) -> if (tier(item) == 2) domainRank(item) else 0 }
            .thenBy { (_, item) -> if (tier(item) == 4) item.downloadBytes else 0L }
            .thenBy { (_, item) -> if (item is ModelEntry && item.role == ModelRole.EMBEDDER) 1 else 0 }
            .thenBy { it.first }
        return items.withIndex().map { it.index to it.value }.sortedWith(order).map { it.second }
    }

    /** The review's one line on the order (Plan DL Decision 5); null when nothing quick is coming. */
    fun quickFirstText(review: Review): UiText? {
        val voice = review.items.any { it is VoiceEntry }
        val classics = review.items.any { it is BookEntry && it.collection in GRANGER_COLLECTIONS }
        return when {
            voice && classics -> UiText.Res(R.string.onboarding_quick_voice_classics)
            voice -> UiText.Res(R.string.onboarding_quick_voice)
            classics -> UiText.Res(R.string.onboarding_quick_classics)
            else -> null
        }
    }

    /** A token's items: a catalog item, a collection's books, or a family's smallest pack. */
    fun resolve(catalog: Catalog, token: String): List<CatalogItem> = when {
        token.startsWith("@") -> catalog.books.filter { it.collection == token.drop(1) }
        token.startsWith("family:") -> listOfNotNull(catalog.packs.filter { it.family == token.removePrefix("family:") }.minByOrNull { it.sizeBytes })
        else -> listOfNotNull(catalog.item(token))
    }

    /**
     * Packs one per line in catalog order; a collection's books as one line, placed where its first book was. Of two
     * flavours of one ZIM (WikiMed with and without pictures) only the larger is listed: the other would be a duplicate
     * of what the phone gets or already holds (Neal, 2026-10-22).
     */
    fun lines(items: List<CatalogItem>, installed: Set<String>): List<Line> {
        val out = ArrayList<Line>()
        val bookLines = LinkedHashMap<String, MutableList<BookEntry>>()
        val largestFlavour = items.filterIsInstance<PackEntry>().filter { it.zimName.isNotBlank() }
            .groupBy { it.zimName }.mapValues { (_, packs) -> packs.maxBy { it.sizeBytes }.id }
        for (item in items) {
            if (item is PackEntry && item.zimName.isNotBlank() && largestFlavour[item.zimName] != item.id) continue
            when (item) {
                is BookEntry -> bookLines.getOrPut(item.collection) { mutableListOf() }.also { if (it.isEmpty()) out += PLACEHOLDER }.add(item)
                else -> out += Line(item.id, UiText.Raw(app.granger.sources.KiwixList.title(item)), listOf(item), installed = item.id in installed)
            }
        }
        var next = bookLines.entries.iterator()
        return out.map { line ->
            if (line !== PLACEHOLDER) line else {
                val (collection, books) = next.next()
                Line("collection:$collection", collectionTitle(collection), books, installed = books.all { it.id in installed })
            }
        }
    }

    private fun collectionTitle(collection: String): UiText = when (collection) {
        CLASSICS -> UiText.Res(R.string.onboarding_granger_classics)
        // Its own name in every language, like its catalog preset's.
        app.granger.core.library.Filing.CLASICOS_GRANGER -> UiText.Raw(app.granger.core.library.Filing.CLASICOS_GRANGER_NAME)
        else -> UiText.Raw(collection.replace('-', ' ').replaceFirstChar { it.uppercase() })
    }

    private val PLACEHOLDER = Line("", UiText.Raw(""), emptyList(), installed = false)
}
