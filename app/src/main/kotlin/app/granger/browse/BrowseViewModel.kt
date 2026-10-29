package app.granger.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.core.library.Filing
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One pack as Browse lists it: the pack itself, and how Ask searches it (null for maps, which say nothing). */
data class PackItem(val pack: Pack, val ask: AskReach? = null)

/** One of Browse's sections, shown only when it holds a pack; [id] keeps its collapsed state. */
data class BrowseSection(val id: String, val title: String, val packs: List<PackItem>)

/** A pack an empty Browse suggests, as Pick a start would: the encyclopedia, and the phone's region's map. */
data class BrowseSuggestion(val title: String, val bytes: Long, val map: Boolean, val domain: String?)

/** Browse's home (Plan U3c Decision 1): the packs by section; with none, [empty], and what to get first. */
data class BrowseUi(val sections: List<BrowseSection>, val suggestions: List<BrowseSuggestion> = emptyList()) {
    val empty: Boolean get() = sections.isEmpty()
}

const val REFERENCE_SECTION = "reference"
const val MAPS_SECTION = "maps"

/** The built-in Maps media type (`LibraryDatabase` MIGRATION_9_10). */
private const val MAPS_TYPE_ID = "maps"

/**
 * Browse's sections (Plan U3c Decision 1): every pack the shelves hold, wherever it's filed, as its own row. A map (filed
 * under Maps, or a `maps_` ZIM moved elsewhere) goes under Maps; every other pack, in the Kiwix folder or a collection
 * the user made, under Encyclopedias & reference. Each section is by title, and shows only when it holds a pack. The
 * Library keeps the books ([app.granger.library.libraryKinds]); no schema change, the filing stays as it is.
 */
fun browseSections(shelves: List<Shelf>, ask: (Pack) -> AskReach? = { null }): List<BrowseSection> {
    val reference = LinkedHashMap<String, Pack>()
    val maps = LinkedHashMap<String, Pack>()
    for (shelf in shelves) for (row in shelf.allCollections) for (pack in row.packs) {
        val isMap = shelf.mediaType.id == MAPS_TYPE_ID || Filing.defaultKey(pack) == MAPS_TYPE_ID
        (if (isMap) maps else reference).putIfAbsent(pack.id, pack)
    }
    fun items(packs: Map<String, Pack>, map: Boolean) =
        packs.values.sortedBy { it.title.lowercase() }.map { PackItem(it, if (map) null else ask(it)) }
    return listOf(
        BrowseSection(REFERENCE_SECTION, "Encyclopedias & reference", items(reference, map = false)),
        BrowseSection(MAPS_SECTION, "Maps", items(maps, map = true)),
    ).filter { it.packs.isNotEmpty() }
}

/**
 * How Ask searches the installed [pack] (Plan U3b Decision 8's line): from its catalog entry when the catalog knows it
 * (by its ZIM's UUID, or the installed download's file), else by its own full-text index. A pack copied onto the phone
 * has no vectors, so it's keywords at best.
 */
fun installedAskReach(pack: Pack, catalog: Catalog?, records: List<DownloadRecord>): AskReach {
    val installed = records.filter { it.state == DownloadState.INSTALLED }
    val entry = catalog?.packs?.let { packs ->
        packs.firstOrNull { it.zimUuid == pack.id }
            ?: installed.firstOrNull { it.kind == ItemKind.PACK && it.filePath == pack.filePath }
                ?.let { record -> packs.firstOrNull { it.id == record.itemId } }
    }
    val vectorParents = installed.filter { it.kind == ItemKind.VECTORS }.mapNotNullTo(HashSet()) { VectorDownloads.parentOf(it.itemId) }
    return entry?.let { askReach(it, vectorParents) }
        ?: if (pack.hasFulltextIndex) AskReach.KEYWORDS else AskReach.NONE
}

/**
 * What an empty Browse suggests (Pick a start's suggestion): the encyclopedia of the Efficient start, and the map of the
 * phone's country ([countryCode]), each when the catalog has one.
 */
fun browseSuggestions(catalog: Catalog?, countryCode: String): List<BrowseSuggestion> {
    if (catalog == null) return emptyList()
    val efficient = catalog.presets.firstOrNull { it.id == OnboardingModel.EFFICIENT }?.items.orEmpty().toSet()
    val encyclopedias = catalog.packs.filter { it.family == "wikipedia" }
    val encyclopedia = encyclopedias.firstOrNull { it.id in efficient } ?: encyclopedias.firstOrNull { it.featured }
    val region = OnboardingModel.suggestedRegion(OnboardingModel.regions(catalog, emptySet()), countryCode)?.pack
    fun PackEntry.suggestion(map: Boolean) = BrowseSuggestion(title, sizeBytes, map, domain.ifBlank { null })
    return listOfNotNull(encyclopedia?.suggestion(map = false), region?.suggestion(map = true))
}

/** Browse's home from the shelves (packs only), the catalog and the downloads (for each pack's Ask line). */
class BrowseViewModel(
    shelves: Shelves,
    private val library: Library,
    private val catalog: CatalogSource? = null,
    records: Flow<List<DownloadRecord>> = flowOf(emptyList()),
    countryCode: String = java.util.Locale.getDefault().country,
) : ViewModel() {
    /** Null until the shelves first load. */
    val ui: StateFlow<BrowseUi?> = combine(
        shelves.shelves, catalog?.state ?: flowOf(null), records,
    ) { list, state, recs ->
        val sections = browseSections(list) { installedAskReach(it, state?.catalog, recs) }
        BrowseUi(sections, if (sections.isEmpty()) browseSuggestions(state?.catalog, countryCode) else emptyList())
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
