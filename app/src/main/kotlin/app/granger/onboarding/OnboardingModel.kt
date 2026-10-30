package app.granger.onboarding

import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
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
    val title: String,
    val items: List<CatalogItem>,
    val installed: Boolean,
) {
    val count: Int get() = items.size
    val bytes: Long get() = items.sumOf { it.downloadBytes }
}

/** Efficient or Comprehensive (spec §6, step 3), built from the generated preset of that name. */
data class StartChoice(val id: String, val title: String, val blurb: String, val lines: List<Line>) {
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

/** What a use-case chip adds beyond what is already chosen or installed (Decision 4). */
data class Addition(val useCase: UseCase, val lines: List<Line>) {
    val bytes: Long get() = lines.sumOf { it.bytes }
    val packs: Int get() = lines.sumOf { it.count }
}

data class ReviewGroup(val title: String, val lines: List<Line>)

/** The review step: every item once, the total against the chosen volume's free space. */
data class Review(
    val groups: List<ReviewGroup>,
    val freeBytes: Long,
    /** What was left out because a bigger pack in the review holds it (Plan WF Decision 5). */
    val notes: List<String> = emptyList(),
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
    const val EFFICIENT = "efficient"
    const val COMPREHENSIVE = "comprehensive"
    const val ASK = "ask"
    const val VOICE = "voice"
    const val VOICE_ID = "vits-piper-en_US-granger_libritts-medium"

    /**
     * The two start choices, each from its generated preset; a catalog without them (older) gives an empty list.
     * [saveSpace] swaps each pack for its no-pictures flavour where the catalog has one (Neal, 2026-10-28: pictures are
     * the default, no-pictures the space-saving fallback).
     */
    fun startChoices(catalog: Catalog, installed: Set<String>, saveSpace: Boolean = false): List<StartChoice> {
        val blurbs = mapOf(
            EFFICIENT to "The essentials of every domain: enough to answer most questions, in the least space.",
            COMPREHENSIVE to "Everything the readiness model counts, for a phone that can hold it.",
        )
        return listOf(EFFICIENT, COMPREHENSIVE).mapNotNull { id ->
            val preset = catalog.presets.firstOrNull { it.id == id } ?: return@mapNotNull null
            val items = preset.items.mapNotNull { catalog.item(it) }.map { if (saveSpace) withoutPictures(catalog, it) else it }.distinctBy { it.id }
            StartChoice(id, preset.title, blurbs.getValue(id), lines(items, installed))
        }
    }

    /**
     * Ask's models as one line (Plan U3a Decision 2): the answering model, plus the embedder when [dense] retrieval is on,
     * less what's installed. Null on a phone that can't run Ask, in a catalog with no answering model (release builds
     * today), or when everything is on the phone already.
     */
    fun askLine(catalog: Catalog, tier: DeviceTier, dense: Boolean, installed: Set<String>): Line? {
        if (tier < DeviceTier.ASK) return null
        val items = Librarian.models(catalog, dense).filter { it.id !in installed }
        return if (items.isEmpty()) null else Line(ASK, "Ask (Preview)", items, installed = false)
    }

    /**
     * The voice offered in Pick a start (Plan U3a, Neal's walkthrough): the catalog's Granger voice of [VOICE_ID], else
     * its first, as one "Read aloud" line. Null with no voices in the catalog or when any catalog voice is installed.
     */
    fun voiceLine(catalog: Catalog, installed: Set<String>): Line? {
        if (catalog.voices.any { it.id in installed }) return null
        val voice = catalog.voices.firstOrNull { it.id == VOICE_ID } ?: catalog.voices.firstOrNull() ?: return null
        return Line(VOICE, "Read aloud", listOf(voice), installed = false)
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

    /** Each use case's lines beyond [chosen] item ids and [installed] ones; a use case left with nothing is kept with no lines. */
    fun additions(catalog: Catalog, installed: Set<String>, chosen: Set<String>): List<Addition> =
        catalog.onboarding?.useCases.orEmpty().map { useCase ->
            val items = useCase.items.flatMap { resolve(catalog, it) }.distinctBy { it.id }.filter { it.id !in chosen }
            Addition(useCase, lines(items, installed).filterNot { it.installed })
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
                s.chosen(unticked).fresh().takeIf { it.isNotEmpty() }?.let { add(ReviewGroup(if (whole) s.title else "Your picks", it)) }
            }
            // Under its own heading the line is "The librarian", not "Ask (Preview)" twice.
            voice?.let { v -> listOf(v.copy(title = v.items.single().title)).fresh().takeIf { it.isNotEmpty() }?.let { add(ReviewGroup(v.title, it)) } }
            ask?.let { listOf(it.copy(title = "The librarian")).fresh().takeIf { l -> l.isNotEmpty() }?.let { l -> add(ReviewGroup(it.title, l)) } }
            region?.takeUnless { it.installed }?.let { r ->
                listOf(Line(r.pack.id, r.title, listOf(r.pack), installed = false)).fresh().takeIf { it.isNotEmpty() }
                    ?.let { add(ReviewGroup("Your region", it)) }
            }
            val useCaseLines = additions.flatMap { it.lines }.fresh()
            if (useCaseLines.isNotEmpty()) add(ReviewGroup("What you'll use it for", useCaseLines))
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
    fun heldText(holder: PackEntry, held: PackEntry): String =
        if (holder.isSlice && held.isSlice && holder.setId == held.setId) {
            val big = app.granger.sources.KiwixList.shortLabel(holder.setLabel)
            val set = app.granger.sources.KiwixList.setTitle(holder.setId, listOf(holder))
            "$set $big covers ${app.granger.sources.KiwixList.shortLabel(held.setLabel)}, so only $big is queued."
        } else {
            "${holder.title} covers ${held.title}, so it isn't queued."
        }

    /** The Granger Classics' collection id. */
    const val CLASSICS = "granger-classics"

    /** Small packs land early (Plan DL Decision 5): 200 MB or less. */
    const val SMALL_PACK_BYTES = 200L * 1000 * 1000

    /** A region's map lands early when it's 2 GB or less; a bigger one waits with the rest. */
    const val QUICK_MAP_BYTES = 2L * 1000 * 1000 * 1000

    /**
     * The order setup downloads [items] in (Plan DL Decision 5, quick wins first): the voice; the Granger Classics; small
     * packs in readiness-domain order ([domains]); the region's map ([regionPackId]) if it isn't big; everything else
     * smallest first (so Wikipedia Top and big maps come last among the packs); then Ask's models, the chat model
     * before the embedder. The review keeps its own grouping; only the queue takes this order.
     */
    fun downloadOrder(items: List<CatalogItem>, domains: List<String>, regionPackId: String?): List<CatalogItem> {
        fun tier(item: CatalogItem): Int = when {
            item is VoiceEntry -> 0
            item is BookEntry && item.collection == CLASSICS -> 1
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
    fun quickFirstText(review: Review): String? {
        val voice = review.items.any { it is VoiceEntry }
        val classics = review.items.any { it is BookEntry && it.collection == CLASSICS }
        return when {
            voice && classics -> "Quick ones first: the voice and the Classics land in a few minutes."
            voice -> "Quick ones first: the voice lands in a few minutes."
            classics -> "Quick ones first: the Classics land in a few minutes."
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
                else -> out += Line(item.id, item.title, listOf(item), installed = item.id in installed)
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

    private fun collectionTitle(collection: String) = when (collection) {
        "granger-classics" -> "Granger Classics"
        else -> collection.replace('-', ' ').replaceFirstChar { it.uppercase() }
    }

    private val PLACEHOLDER = Line("", "", emptyList(), installed = false)
}
