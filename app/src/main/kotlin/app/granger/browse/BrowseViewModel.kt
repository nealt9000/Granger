package app.granger.browse

import app.granger.invoke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.R
import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.Domain
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.SidecarKind
import app.granger.core.library.Filing
import app.granger.core.library.isBookPack
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.onboarding.OnboardingModel
import app.granger.sources.AskReach
import app.granger.sources.askReach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One pack as Browse lists it: the pack itself, and how Ask searches it (null for maps, which say nothing). */
data class PackItem(
    val pack: Pack,
    val ask: AskReach? = null,
    /** The PDFs and EPUBs a website pack holds, by its Counter (Plan KB2 Task 4.6): its menu offers Files in this pack. */
    val files: Int = 0,
)

/** One of Browse's sections, shown only when it holds a pack; [id] keeps its collapsed state. */
data class BrowseSection(val id: String, val title: UiText, val packs: List<PackItem>)

/** A pack an empty Browse suggests, as Pick a start would: the encyclopedia, and the phone's region's map. */
data class BrowseSuggestion(val title: String, val bytes: Long, val map: Boolean, val domain: String?)

/** Browse's home (Plan U3c Decision 1): the packs by section; with none, [empty], and what to get first. */
data class BrowseUi(
    val sections: List<BrowseSection>,
    val suggestions: List<BrowseSuggestion> = emptyList(),
    /** Every book collection on the phone ([bookCollectionCount]): the Books row's count (Plan EX Task 4); 0 hides it. */
    val bookCollections: Int = 0,
) {
    /** No packs: what to get first shows (the Books row too, when there are books). */
    val empty: Boolean get() = sections.isEmpty()
}

/**
 * How many book collections the phone holds (Plan EX Task 4), as the Library files them: each book pack's (Gutenberg,
 * Kiwix libraries, manifest packs) and each of Granger's (Classics, Clásicos, each African Storybook language). A pack's
 * books share its pack's collection, so they count once.
 */
fun bookCollectionCount(packs: List<Pack>, books: List<app.granger.core.library.Book>): Int {
    val keys = HashSet<String>()
    packs.filter { it.isBookPack }.mapTo(keys) { Filing.defaultKey(it) }
    books.filter { it.origin == app.granger.core.library.BookOrigin.GRANGER }.mapTo(keys) { Filing.defaultKey(it) }
    return keys.size
}

/** The section of packs whose catalog entry has no knowledge area (or that the catalog doesn't know): just before Maps. */
const val OTHER_SECTION = "other"

/**
 * The section of map files (Plan U3c Decision 1), last. Not `maps`: that's the catalog's "Maps, places & travel" domain,
 * which holds packs like Wikivoyage and gets its own section.
 */
const val MAPS_SECTION = "map-files"

/** The built-in Maps media type (`LibraryDatabase` MIGRATION_9_10). */
private const val MAPS_TYPE_ID = "maps"

/**
 * Browse's sections (Plan U3c Decision 1): every pack the shelves hold, wherever it's filed, as its own row. A map (filed
 * under Maps, or a `maps_` ZIM moved elsewhere) goes under Maps, last. Every other pack, in the Kiwix folder or a
 * collection the user made, goes under its knowledge area ([domainOf], the catalog entry's readiness domain id), in the
 * order and with the titles of [domains]; a pack with no domain, or one [domains] doesn't list, goes under Other, before
 * Maps. A section's id is its domain's, so its collapsed state keeps. Each section is by title, and shows only when it
 * holds a pack. The Library keeps the books ([app.granger.library.libraryKinds]); no schema change, the filing stays.
 */
fun browseSections(
    shelves: List<Shelf>,
    domains: List<Domain> = emptyList(),
    domainOf: (Pack) -> String? = { null },
    ask: (Pack) -> AskReach? = { null },
): List<BrowseSection> {
    val known = domains.mapTo(HashSet()) { it.id }
    val byDomain = LinkedHashMap<String, LinkedHashMap<String, Pack>>()
    val maps = LinkedHashMap<String, Pack>()
    for (shelf in shelves) for (row in shelf.allCollections) for (pack in row.packs) {
        // A book pack's way in is its books, in the Library (Plan GB Decision 1, Plan KB Decision 6).
        if (pack.isBookPack) continue
        val isMap = shelf.mediaType.id == MAPS_TYPE_ID || Filing.defaultKey(pack) == MAPS_TYPE_ID
        if (isMap) { maps.putIfAbsent(pack.id, pack); continue }
        val domain = domainOf(pack)?.takeIf { it.isNotBlank() && it in known } ?: OTHER_SECTION
        // A pack filed twice (a user collection and the Kiwix folder) is listed once, under its first domain.
        if (byDomain.values.none { pack.id in it }) byDomain.getOrPut(domain) { LinkedHashMap() }[pack.id] = pack
    }
    fun items(packs: Map<String, Pack>?, map: Boolean) =
        packs.orEmpty().values.sortedBy { it.title.lowercase() }.map { PackItem(it, if (map) null else ask(it)) }
    return (
        domains.map { BrowseSection(it.id, UiText.Raw(it.title(java.util.Locale.getDefault().language)), items(byDomain[it.id], map = false)) } +
            BrowseSection(OTHER_SECTION, UiText.Res(R.string.browse_section_other), items(byDomain[OTHER_SECTION], map = false)) +
            BrowseSection(MAPS_SECTION, UiText.Res(R.string.browse_section_maps), items(maps, map = true))
        ).filter { it.packs.isNotEmpty() }
}

/**
 * The catalog's entry for the installed [pack] (Plan U3b Decision 8's match): by its ZIM's UUID, else by the installed
 * download's file. Null with no catalog, or for a pack copied onto the phone.
 */
fun installedEntry(pack: Pack, catalog: Catalog?, records: List<DownloadRecord>): PackEntry? = catalog?.packs?.let { packs ->
    packs.firstOrNull { it.zimUuid == pack.id }
        ?: records.firstOrNull { it.state == DownloadState.INSTALLED && it.kind == ItemKind.PACK && it.filePath == pack.filePath }
            ?.let { record -> packs.firstOrNull { it.id == record.itemId } }
}

/**
 * How Ask searches the installed [pack] (Plan U3b Decision 8's line): from its catalog entry when the catalog knows it
 * ([installedEntry]), else by its own full-text index. A pack copied onto the phone has no vectors, so it's keywords at best.
 */
fun installedAskReach(pack: Pack, catalog: Catalog?, records: List<DownloadRecord>): AskReach {
    val installed = records.filter { it.state == DownloadState.INSTALLED }
    val entry = installedEntry(pack, catalog, records)
    val vectorParents = installed.filter { it.kind == ItemKind.VECTORS }.mapNotNullTo(HashSet()) { SidecarKind.VECTORS.parentOf(it.itemId) }
    return entry?.let { askReach(it, vectorParents) }
        ?: if (pack.hasFulltextIndex) AskReach.KEYWORDS else AskReach.NONE
}

/**
 * What an empty Browse suggests (Pick a start's suggestion): the encyclopedia of the Efficient start in the main library
 * [language] (Plan L3), and the map of the phone's country ([countryCode]), each when the catalog has one.
 */
fun browseSuggestions(catalog: Catalog?, countryCode: String, language: String = app.granger.core.catalog.ENGLISH): List<BrowseSuggestion> {
    if (catalog == null) return emptyList()
    val efficient = catalog.tierPresets(language).firstOrNull()?.items.orEmpty().toSet()
    val encyclopedias = catalog.packs.filter { it.family == "wikipedia" }
    val encyclopedia = encyclopedias.firstOrNull { it.id in efficient } ?: encyclopedias.firstOrNull { it.featured }
    val region = OnboardingModel.suggestedRegion(OnboardingModel.regions(catalog, emptySet()), countryCode)?.pack
    fun PackEntry.suggestion(map: Boolean) = BrowseSuggestion(app.granger.sources.KiwixList.title(this), sizeBytes, map, domain.ifBlank { null })
    return listOfNotNull(encyclopedia?.suggestion(map = false), region?.suggestion(map = true))
}

/** Browse's home from the shelves (packs only), the catalog and the downloads (for each pack's Ask line). */
class BrowseViewModel(
    shelves: Shelves,
    private val library: Library,
    private val catalog: CatalogSource? = null,
    records: Flow<List<DownloadRecord>> = flowOf(emptyList()),
    countryCode: String = java.util.Locale.getDefault().country,
    /** The library's languages, main first (Plan L3): the suggestion is the main one's encyclopedia. */
    knowledge: Flow<List<String>> = flowOf(listOf(app.granger.core.catalog.ENGLISH)),
    /** False on a phone without Ask: the packs carry no Ask line (Plan E1 phone check). */
    askOffered: Boolean = true,
    /** The shelf's books, for the Books row's count of collections (Plan EX Task 4). */
    books: Flow<List<app.granger.core.library.Book>> = flowOf(emptyList()),
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : ViewModel() {
    /** Each website pack's file count, read once per pack from its Counter (opening the ZIM), off the main thread. */
    private val countsCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fileCounts: Flow<Map<String, Int>> = library.packs.map { packs ->
        kotlinx.coroutines.withContext(io) {
            packs.filter { it.status == app.granger.core.library.PackStatus.OK && !it.isBookPack }.associate { pack ->
                pack.id to countsCache.getOrPut(pack.id) {
                    runCatching { library.archive(pack.id)?.metadata?.counter?.let(app.granger.core.library.PackFiles::count) }.getOrNull() ?: 0
                }
            }
        }
    }.onStart { emit(emptyMap()) }

    /** Null until the shelves first load. */
    private val bookCollections: Flow<Int> = combine(library.packs, books) { p, b -> bookCollectionCount(p, b) }.onStart { emit(0) }

    val ui: StateFlow<BrowseUi?> = combine(
        combine(shelves.shelves, catalog?.state ?: flowOf(null), records) { a, b, c -> Triple(a, b, c) },
        knowledge, fileCounts, bookCollections,
    ) { (list, state, recs), langs, counts, booksN ->
        val catalog = state?.catalog
        val sections = browseSections(
            list,
            domains = catalog?.readiness?.domains.orEmpty(),
            domainOf = { installedEntry(it, catalog, recs)?.domain },
        ) { if (askOffered) installedAskReach(it, catalog, recs) else null }
            .map { section -> section.copy(packs = section.packs.map { it.copy(files = counts[it.pack.id] ?: 0) }) }
        BrowseUi(
            sections,
            if (sections.isEmpty()) browseSuggestions(state?.catalog, countryCode, langs.firstOrNull() ?: app.granger.core.catalog.ENGLISH) else emptyList(),
            booksN,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { catalog?.load() }
    }

    /** Re-scan pack folders; called whenever the screen resumes (an SD card may have come back). */
    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }

    /** Deletes the pack's file from the phone, as the collection screen's Remove did. */
    fun remove(pack: Pack) {
        viewModelScope.launch { library.remove(pack.id) }
    }
}
