package app.granger.home

import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeShelves
import app.granger.FakeZimArchive
import app.granger.MainDispatcherRule
import app.granger.catalogOf
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Domain
import app.granger.core.catalog.Readiness
import app.granger.core.downloads.DownloadState
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeDraw
import app.granger.core.library.LibrarySearch
import app.granger.core.library.PackStatus
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.zim.ZimHit
import app.granger.music.FakeMediaLibrary
import app.granger.pack
import app.granger.packEntry
import app.granger.readiness.Coverage
import app.granger.readiness.ReadinessReport
import app.granger.record
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import app.granger.shell.RailUi
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = FakeLibrary()
    private val bookshelf = FakeBookshelf()
    private val downloads = FakeDownloads()
    private val catalog = FakeCatalogSource()
    private val shelves = FakeShelves()
    private val media = FakeMediaLibrary()
    private val homeFeeds = FakeHomeFeeds()
    private val prefs = FakeHomePrefs()
    private val rail = MutableStateFlow(RailUi(emptyList()))
    private var today = LocalDate.of(2026, 10, 22)

    private val med = packEntry("wikipedia_en_medicine_nopic", zimName = "wikipedia_en_medicine").copy(domain = "medicine")
    private val food = packEntry("cooking.stackexchange.com_en_all", zimName = "cooking.stackexchange.com_en", size = 500).copy(domain = "food")
    private val domains = listOf(
        Domain("medicine", "Medicine & first aid", listOf(med.id), listOf(med.id)),
        Domain("food", "Food, water & survival", listOf(food.id), listOf(food.id)),
    )
    private val wikipedia = pack("u1", "Wikipedia").copy(name = "wikipedia_en_all")
    private val archive = FakeZimArchive(
        uuid = "u1",
        paths = setOf("index", "Cat"),
        random = "Cat",
        results = (1..6).map { ZimHit("Hit_$it", "Hit $it") },
        contents = mapOf("Cat" to "<html><head><title>Cat</title></head><body></body></html>"),
    )
    private val librarySearch = object : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults(emptyList())
        override suspend fun fullText(query: String) = emptyList<SearchHit>()
    }

    private fun TestScope.viewModel(): HomeViewModel {
        val drawer = FeedDrawer({ library.archives[it.id] }, { emptyList() }, { emptyList() }, librarySearch)
        val vm = HomeViewModel(
            catalog, library, bookshelf, downloads, rail, homeFeeds, drawer, shelves, media, prefs,
            io = main.dispatcher, today = { today },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    private fun tab(id: Long, title: String) =
        RailEntry.Tab(RailTabUi(id, title, "A pack", title.first(), available = true, current = false), at = id)

    private fun installWikipedia() {
        library.packList.value = listOf(wikipedia)
        library.archives["u1"] = archive
    }

    @Test fun stripIsTheReadinessReportOverTheCachedCatalog() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = catalogOf(packs = listOf(med, food)).copy(readiness = Readiness(domains)))
        library.packList.value = listOf(pack("uuid-med", "WikiMed").copy(name = "wikipedia_en_medicine"))
        downloads.list.value = listOf(record(med.id, DownloadState.INSTALLED))
        val ui = viewModel().ui.value
        assertThat(ui.strip.domains.map { it.id to it.coverage })
            .containsExactly("medicine" to Coverage.COMPREHENSIVE, "food" to Coverage.NONE).inOrder()
        assertThat(ui.strip.signals.single().text).startsWith("Nothing yet for Food, water & survival.")
        assertThat(ui.strip.needsCatalogRefresh).isFalse()
    }

    @Test fun noCatalogMeansARefreshIsNeeded() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.strip.domains).isEmpty()
        assertThat(ui.strip.needsCatalogRefresh).isTrue()
        assertThat(stripText(ui.strip)).isEqualTo("Refresh the catalog to see your coverage.")
    }

    @Test fun continueIsTheRailsTabsAndPlaysInTheRailsOrder() = runTest(main.dispatcher) {
        val owls = tab(1, "Owls")
        val blue = RailEntry.Play("album:Blue", "Blue", at = 3)
        rail.value = RailUi(
            listOf(RailEntry.Search("cats", at = 9), blue, RailEntry.Chat(4, "About owls", at = 2), owls, RailEntry.Reading("k", "Live")),
        )
        val vm = viewModel()
        assertThat(vm.ui.value.continueCards).containsExactly(blue, owls).inOrder()

        rail.value = RailUi((1L..12L).map { tab(it, "T$it") })
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.continueCards).hasSize(HomeViewModel.CONTINUE)
    }

    @Test fun dotsAndTheSentenceFollowTheReport() {
        assertThat(dotAlpha(Coverage.NONE)).isLessThan(dotAlpha(Coverage.BASIC))
        assertThat(dotAlpha(Coverage.BASIC)).isLessThan(dotAlpha(Coverage.COMPREHENSIVE))
        assertThat(dotAlpha(Coverage.COMPREHENSIVE)).isEqualTo(1f)
        val ready = ReadinessReport(emptyList(), emptyList(), emptyList(), needsCatalogRefresh = false)
        assertThat(stripText(ready)).isEqualTo("Ready.")
    }

    @Test fun theDefaultsAreSeededOnceAndDormantOnesStayHidden() = runTest(main.dispatcher) {
        installWikipedia()
        val vm = viewModel()
        assertThat(homeFeeds.list.value.map { it.scope to it.kind }).containsExactlyElementsIn(
            HomeViewModel.DEFAULTS.map { (scope, kind) -> scope.text to kind },
        ).inOrder()
        assertThat(prefs.seeded).isTrue()
        // Wikipedia is installed, the Classics and Wikiquote are not: two rows show, two are hidden.
        assertThat(vm.ui.value.feeds.map { it.feed.kind }).containsExactly(FeedKind.ON_THIS_DAY, FeedKind.RANDOM).inOrder()
        assertThat(vm.ui.value.feeds.map { it.scopeName }.distinct()).containsExactly("Wikipedia")

        // Removing every row and starting again seeds nothing: the mark is set.
        homeFeeds.list.value.forEach { homeFeeds.remove(it.id) }
        viewModel()
        assertThat(homeFeeds.list.value).isEmpty()
    }

    @Test fun aDrawIsCachedForTheDayAndRedrawnTheNext() = runTest(main.dispatcher) {
        installWikipedia()
        prefs.seeded = true
        val id = homeFeeds.add("pack:wikipedia_en_all", FeedKind.RANDOM)
        val vm = viewModel()
        val row = vm.ui.value.feeds.single()
        assertThat(row.drawn!!.title).isEqualTo("Random from Wikipedia")
        assertThat(row.drawn!!.cards.single().title).isEqualTo("Cat")
        assertThat(homeFeeds.draws[id]!!.day).isEqualTo("2026-10-22")

        // A fresh look the same day shows the cache as is, even when the archive would now say otherwise.
        val cached = Drawn("Random from Wikipedia", listOf(Card("Cached", "Wikipedia", CardTarget.Article("u1", "Cached"))))
        homeFeeds.draws[id] = HomeDraw(id, "2026-10-22", Cards.encode(cached))
        val again = viewModel()
        assertThat(again.ui.value.feeds.single().drawn!!.cards.single().title).isEqualTo("Cached")

        // The next day (a resume after midnight): redrawn and the cache replaced.
        today = today.plusDays(1)
        again.refresh()
        testScheduler.advanceUntilIdle()
        assertThat(again.ui.value.feeds.single().drawn!!.cards.single().title).isEqualTo("Cat")
        assertThat(homeFeeds.draws[id]!!.day).isEqualTo("2026-10-23")
    }

    @Test fun aCachedCardWhosePackIsGoneIsDroppedAndTheRowRedrawn() = runTest(main.dispatcher) {
        installWikipedia()
        prefs.seeded = true
        val id = homeFeeds.add("pack:wikipedia_en_all", FeedKind.RANDOM)
        val stale = Drawn("Random from Wikipedia", listOf(Card("Old", "Gone pack", CardTarget.Article("u-gone", "Old"))))
        homeFeeds.draws[id] = HomeDraw(id, "2026-10-22", Cards.encode(stale))
        val vm = viewModel()
        assertThat(vm.ui.value.feeds.single().drawn!!.cards.single().title).isEqualTo("Cat")
    }

    @Test fun addRemoveAndMoveGoToTheStore() = runTest(main.dispatcher) {
        installWikipedia()
        prefs.seeded = true
        val vm = viewModel()
        vm.addFeed(FeedScope.Pack("wikipedia_en_all"), FeedKind.SEARCH, "cats")
        vm.addFeed(FeedScope.Library, FeedKind.RANDOM)
        testScheduler.advanceUntilIdle()
        assertThat(homeFeeds.list.value.map { it.scope }).containsExactly("pack:wikipedia_en_all", "library").inOrder()
        assertThat(vm.ui.value.feeds.map { it.drawn!!.title }).containsExactly("cats · Wikipedia", "Random from the library").inOrder()
        vm.moveFeed(homeFeeds.list.value.last().id, up = true)
        testScheduler.advanceUntilIdle()
        assertThat(homeFeeds.list.value.map { it.scope }).containsExactly("library", "pack:wikipedia_en_all").inOrder()
        vm.removeFeed(homeFeeds.list.value.first().id)
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.feeds.map { it.feed.scope }).containsExactly("pack:wikipedia_en_all")
    }

    @Test fun theSetupStripFollowsTheNewestOnboardingGroupUntilItLands() = runTest(main.dispatcher) {
        prefs.seeded = true
        val a = record("a", DownloadState.INSTALLED).copy(groupId = "onboarding:1", sizeBytes = 100)
        val b = record("b", DownloadState.RUNNING).copy(groupId = "onboarding:1", sizeBytes = 300, bytesDone = 50)
        val c = record("c", DownloadState.FAILED).copy(groupId = "onboarding:1", sizeBytes = 100)
        val old = record("z", DownloadState.QUEUED).copy(groupId = "onboarding:0", sizeBytes = 999)
        downloads.list.value = listOf(a, b, c, old)
        val vm = viewModel()
        val setup = vm.ui.value.setup!!
        assertThat(setup).isEqualTo(SetupProgress(done = 1, total = 3, bytesDone = 150, bytes = 500, failed = 1))
        assertThat(setupText(setup)).isEqualTo("Setting up · 1 of 3 · 150 B of 500 B · 1 failed, tap to retry")
        downloads.list.value = listOf(a, b.copy(state = DownloadState.INSTALLED), c.copy(state = DownloadState.INSTALLED), old)
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.setup).isNull()
        assertThat(HomeViewModel.setupProgress(emptyList())).isNull()
    }

    @Test fun scopeChoicesOfferWhatIsOnThePhone() = runTest(main.dispatcher) {
        library.packList.value = listOf(wikipedia, pack("u2", "Gone", status = PackStatus.MISSING).copy(name = "x_en"), pack("u3", "No name"))
        prefs.seeded = true
        val choices = viewModel().ui.value.scopeChoices
        assertThat(choices.map { it.label }).containsExactly("Whole library", "Wikipedia").inOrder()
        assertThat(choices.last().scope).isEqualTo(FeedScope.Pack("wikipedia_en_all"))
    }
}
