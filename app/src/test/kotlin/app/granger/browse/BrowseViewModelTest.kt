package app.granger.browse

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.book
import app.granger.catalogOf
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Preset
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VectorDownloads
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import app.granger.sources.AskReach
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Browse holds every pack and map as its own row (Plan U3c Decision 1); the Library keeps the books. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val shelves = FakeShelves()
    private val library = FakeLibrary()
    private val downloads = FakeDownloads()

    private fun row(id: String, type: String, vararg packs: app.granger.core.library.Pack, books: Int = 0, userCreated: Boolean = false) =
        CollectionRow(
            ShelfCollection("c:$id", id, type, userCreated = userCreated, defaultKey = id.takeIf { !userCreated }), packs.toList(),
            books = List(books) { book("$id-$it") },
        )

    private fun shelf(type: String, vararg rows: CollectionRow) =
        Shelf(MediaType(type.lowercase(), type, builtIn = true, sortOrder = 0), rows.toList())

    private val wikipedia = pack("u-wiki", "Wikipedia").copy(name = "wikipedia_en_all_maxi")
    private val devdocs = pack("u-dev", "DevDocs: Kotlin").copy(name = "devdocs_en_kotlin")
    private val iceland = pack("u-ice", "Iceland").copy(name = "maps_iceland")
    private val folder = CollectionRow(
        ShelfCollection("c:kiwix", "Kiwix", "literature", userCreated = false, defaultKey = "kiwix"), emptyList(),
        children = listOf(row("Wikipedia", "literature", wikipedia), row("DevDocs", "literature", devdocs)),
    )

    @Test fun thePacksThemselvesAreRowsInTheirSections() = runTest(main.dispatcher) {
        val mine = row("Medicine", "literature", pack("u-med", "Medicine"), userCreated = true)
        shelves.shelfList.value = listOf(
            shelf("Literature", row("Granger Classics", "literature", books = 3), folder, mine),
            shelf("Maps", row("OpenStreetMap", "maps", iceland)),
            shelf("Music", row("Phone", "music")),
        )
        val ui = BrowseViewModel(shelves, library).ui.filterNotNull().first()
        // No catalog: every pack that isn't a map is Other.
        assertThat(ui.sections.map { it.title }).containsExactly("Other", "Maps").inOrder()
        // Not one Kiwix folder: each pack, by title, wherever it's filed (the user's own collection too). No books.
        assertThat(ui.sections[0].packs.map { it.pack.title }).containsExactly("DevDocs: Kotlin", "Medicine", "Wikipedia").inOrder()
        assertThat(ui.sections[1].packs.map { it.pack.title }).containsExactly("Iceland")
        assertThat(ui.sections.map { it.id }).containsExactly(OTHER_SECTION, MAPS_SECTION).inOrder()
        assertThat(ui.empty).isFalse()
        assertThat(ui.suggestions).isEmpty()
    }

    @Test fun eachSectionShowsOnlyWhenItHoldsAPack() {
        assertThat(browseSections(listOf(shelf("Maps", row("OpenStreetMap", "maps", iceland)))).map { it.id }).containsExactly(MAPS_SECTION)
        assertThat(browseSections(listOf(shelf("Literature", folder))).map { it.id }).containsExactly(OTHER_SECTION)
        assertThat(browseSections(listOf(shelf("Literature", row("Classics", "literature", books = 2))))).isEmpty()
    }

    /** A map is a map wherever the user filed it; a pack moved into Maps is listed there. */
    @Test fun mapsGoUnderMaps() {
        val moved = shelf("Literature", row("Places", "literature", iceland, userCreated = true))
        assertThat(browseSections(listOf(moved)).single().id).isEqualTo(MAPS_SECTION)
        val intoMaps = shelf("Maps", row("Mine", "maps", devdocs, userCreated = true))
        assertThat(browseSections(listOf(intoMaps)).single().id).isEqualTo(MAPS_SECTION)
    }

    /**
     * Packs group by their catalog entry's knowledge area, in the readiness model's order and titles; Wikivoyage's
     * `maps` domain is its own section, apart from the map files. A pack the catalog doesn't know, or one with no domain,
     * is Other, just before Maps.
     */
    @Test fun packsGroupByKnowledgeArea() = runTest(main.dispatcher) {
        val wikimed = pack("u-med", "WikiMed")
        val wikem = pack("u-em", "WikEM")
        val knots = pack("u-knots", "Knots")
        val voyage = pack("u-voy", "Wikivoyage").copy(name = "wikivoyage_en_all_nopic")
        val game = pack("u-game", "Balatro wiki")
        val sideloaded = pack("u-side", "Sideloaded")
        shelves.shelfList.value = listOf(
            shelf("Literature", folder, row("Mine", "literature", wikimed, wikem, knots, voyage, game, sideloaded, userCreated = true)),
            shelf("Maps", row("OpenStreetMap", "maps", iceland)),
        )
        val domains = listOf(
            Domain("medicine", "Medicine & first aid"), Domain("making", "Making, repair & engineering"),
            Domain("maps", "Maps, places & travel"), Domain("reference", "General reference"), Domain("thought", "Religion, philosophy & history"),
        )
        val catalog = catalogOf(
            packs = listOf(
                packEntry("wikipedia").copy(zimUuid = "u-wiki", domain = "reference"),
                packEntry("wikimed").copy(zimUuid = "u-med", domain = "medicine"),
                packEntry("wikem").copy(zimUuid = "u-old-snapshot", domain = "medicine"),
                packEntry("knots").copy(zimUuid = "u-knots", domain = "making"),
                packEntry("voyage").copy(zimUuid = "u-voy", domain = "maps"),
                packEntry("balatro").copy(zimUuid = "u-game"),
            ),
        ).copy(readiness = Readiness(domains))
        // WikEM's UUID changed since the catalog was signed: its installed download's file still finds the entry.
        downloads.list.value = listOf(record("wikem", DownloadState.INSTALLED).copy(filePath = wikem.filePath))
        val ui = BrowseViewModel(shelves, library, FakeCatalogSource(catalog), downloads.records).ui.filterNotNull()
            .first { it.sections.size > 2 }
        assertThat(ui.sections.map { it.id }).containsExactly("medicine", "making", "maps", "reference", OTHER_SECTION, MAPS_SECTION).inOrder()
        assertThat(ui.sections.map { it.title }).containsExactly(
            "Medicine & first aid", "Making, repair & engineering", "Maps, places & travel", "General reference", "Other", "Maps",
        ).inOrder()
        val titles = ui.sections.associate { it.id to it.packs.map { item -> item.pack.title } }
        assertThat(titles["medicine"]).containsExactly("WikEM", "WikiMed").inOrder()
        assertThat(titles["maps"]).containsExactly("Wikivoyage")
        assertThat(titles["reference"]).containsExactly("Wikipedia")
        assertThat(titles[OTHER_SECTION]).containsExactly("Balatro wiki", "DevDocs: Kotlin", "Sideloaded").inOrder()
        assertThat(titles[MAPS_SECTION]).containsExactly("Iceland")
    }

    @Test fun eachPackSaysHowAskSearchesItButMapsSayNothing() = runTest(main.dispatcher) {
        val unindexed = pack("u-nofts", "Quotes").copy(hasFulltextIndex = false)
        val sideloaded = pack("u-side", "Sideloaded")
        shelves.shelfList.value = listOf(
            shelf("Literature", folder, row("Other", "literature", unindexed, sideloaded)),
            shelf("Maps", row("OpenStreetMap", "maps", iceland)),
        )
        // Wikipedia has its search vectors installed; DevDocs is in the catalog with a keyword index only.
        val catalog = catalogOf(packs = listOf(packEntry("wiki").copy(zimUuid = "u-wiki"), packEntry("dev").copy(zimUuid = "u-dev")))
        downloads.list.value = listOf(record(VectorDownloads.itemIdOf("wiki"), DownloadState.INSTALLED, kind = ItemKind.VECTORS))
        val ui = BrowseViewModel(shelves, library, FakeCatalogSource(catalog), downloads.records).ui.filterNotNull().first { it.sections.size == 2 }
        val ask = ui.sections[0].packs.associate { it.pack.title to it.ask }
        assertThat(ask).containsExactly(
            "DevDocs: Kotlin", AskReach.KEYWORDS, "Quotes", AskReach.NONE, "Sideloaded", AskReach.KEYWORDS, "Wikipedia", AskReach.MEANING,
        )
        assertThat(ui.sections[1].packs.single().ask).isNull()
    }

    @Test fun anEmptyBrowseSuggestsAnEncyclopediaAndTheRegionsMap() = runTest(main.dispatcher) {
        val wiki = packEntry("wikipedia_en_top").copy(family = "wikipedia", domain = "reference")
        val france = packEntry("maps_france", size = 300_000_000).copy(title = "France", family = "maps", domain = "maps")
        val catalog = catalogOf(packs = listOf(wiki, france), presets = listOf(Preset("efficient", "Efficient", listOf(wiki.id))))
        val ui = BrowseViewModel(shelves, library, FakeCatalogSource(catalog), countryCode = "FR").ui.filterNotNull().first()
        assertThat(ui.empty).isTrue()
        assertThat(ui.suggestions.map { it.title }).containsExactly("Pack wikipedia_en_top", "France").inOrder()
        assertThat(ui.suggestions.map { it.map }).containsExactly(false, true).inOrder()
        assertThat(ui.suggestions.map { it.domain }).containsExactly("reference", "maps").inOrder()
        // No catalog yet: nothing to suggest, and the screen still offers Get packs.
        assertThat(browseSuggestions(null, "FR")).isEmpty()
        // A phone in a country with no map pack gets the encyclopedia alone.
        assertThat(browseSuggestions(catalog, "JP").map { it.title }).containsExactly("Pack wikipedia_en_top")
    }

    @Test fun refreshRescansAndRemoveDeletesThePack() = runTest(main.dispatcher) {
        val vm = BrowseViewModel(shelves, library)
        vm.refresh()
        vm.remove(wikipedia)
        testScheduler.advanceUntilIdle()
        assertThat(library.refreshes).isEqualTo(1)
        assertThat(library.removed).containsExactly("u-wiki")
    }
}
