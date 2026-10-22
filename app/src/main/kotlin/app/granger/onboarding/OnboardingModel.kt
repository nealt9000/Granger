package app.granger.onboarding

import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.UseCase

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
    val bytes: Long get() = items.sumOf { it.sizeBytes }
}

/** Efficient or Comprehensive (spec §6, step 3), built from the generated preset of that name. */
data class StartChoice(val id: String, val title: String, val blurb: String, val lines: List<Line>) {
    /** What downloading it would add, with [unticked] lines and installed ones left out. */
    fun bytes(unticked: Set<String> = emptySet()): Long = chosen(unticked).sumOf { it.bytes }

    fun chosen(unticked: Set<String> = emptySet()): List<Line> = lines.filter { !it.installed && it.id !in unticked }
}

/** A map pack the region picker offers. */
data class Region(val pack: PackEntry, val installed: Boolean) {
    val title: String get() = pack.title
    val bytes: Long get() = pack.sizeBytes
}

/** Countries first, then continents and multi-country areas (Plan H3, Decision 3). */
data class Regions(val countries: List<Region>, val larger: List<Region>) {
    fun matching(query: String): Regions {
        val q = query.trim()
        if (q.isEmpty()) return this
        fun List<Region>.hit() = filter { it.title.contains(q, ignoreCase = true) }
        return Regions(countries.hit(), larger.hit())
    }
}

/** What a use-case chip adds beyond what is already chosen or installed (Decision 4). */
data class Addition(val useCase: UseCase, val lines: List<Line>) {
    val bytes: Long get() = lines.sumOf { it.bytes }
    val packs: Int get() = lines.sumOf { it.count }
}

data class ReviewGroup(val title: String, val lines: List<Line>)

/** The review step: every item once, the total against the chosen volume's free space. */
data class Review(val groups: List<ReviewGroup>, val freeBytes: Long) {
    val lines: List<Line> get() = groups.flatMap { it.lines }
    val items: List<CatalogItem> get() = lines.flatMap { it.items }
    val bytes: Long get() = items.sumOf { it.sizeBytes }
    val fits: Boolean get() = bytes <= freeBytes
    val isEmpty: Boolean get() = items.isEmpty()
}

/** The onboarding flow's data, computed from the cached catalog and what is installed. Pure. */
object OnboardingModel {
    const val EFFICIENT = "efficient"
    const val COMPREHENSIVE = "comprehensive"

    /** The two start choices, each from its generated preset; a catalog without them (older) gives an empty list. */
    fun startChoices(catalog: Catalog, installed: Set<String>): List<StartChoice> {
        val blurbs = mapOf(
            EFFICIENT to "The essentials of every domain: enough to answer most questions, in the least space.",
            COMPREHENSIVE to "Everything the readiness model counts, for a phone that can hold it.",
        )
        return listOf(EFFICIENT, COMPREHENSIVE).mapNotNull { id ->
            val preset = catalog.presets.firstOrNull { it.id == id } ?: return@mapNotNull null
            val items = preset.items.mapNotNull { catalog.item(it) }
            StartChoice(id, preset.title, blurbs.getValue(id), lines(items, installed))
        }
    }

    /** The maps family split by [app.granger.core.catalog.Onboarding.larger], each list by title. */
    fun regions(catalog: Catalog, installed: Set<String>): Regions {
        val larger = catalog.onboarding?.larger.orEmpty().toSet()
        val maps = catalog.packs.filter { it.family == "maps" }.map { Region(it, it.id in installed) }.sortedBy { it.title.lowercase() }
        return Regions(countries = maps.filter { it.pack.id !in larger }, larger = maps.filter { it.pack.id in larger })
    }

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
    ): Review {
        val seen = HashSet<String>()
        fun List<Line>.fresh(): List<Line> = mapNotNull { line ->
            val items = line.items.filter { seen.add(it.id) }
            if (items.isEmpty()) null else line.copy(items = items)
        }
        val groups = buildList {
            start?.let { s -> s.chosen(unticked).fresh().takeIf { it.isNotEmpty() }?.let { add(ReviewGroup(s.title, it)) } }
            region?.takeUnless { it.installed }?.let { r ->
                listOf(Line(r.pack.id, r.title, listOf(r.pack), installed = false)).fresh().takeIf { it.isNotEmpty() }
                    ?.let { add(ReviewGroup("Your region", it)) }
            }
            val useCaseLines = additions.flatMap { it.lines }.fresh()
            if (useCaseLines.isNotEmpty()) add(ReviewGroup("Your situation", useCaseLines))
        }
        return Review(groups, freeBytes)
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
