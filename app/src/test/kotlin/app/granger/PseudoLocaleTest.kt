package app.granger

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.granger.archive.ArchivePrefs
import app.granger.theme.GrangerTheme
import app.granger.core.library.CollectionRow
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.LibrarySearch
import app.granger.core.library.MediaType
import app.granger.core.library.SearchHit
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.core.library.TitleResults
import app.granger.home.Card
import app.granger.home.CardTarget
import app.granger.home.Drawn
import app.granger.home.FeedUi
import app.granger.home.HomeContent
import app.granger.home.HomeUi
import app.granger.library.LibraryContent
import app.granger.library.LibraryUi
import app.granger.library.libraryKinds
import app.granger.player.FakePlayerHub
import app.granger.player.ListenPlayerScreen
import app.granger.player.PlayerViewModel
import app.granger.player.musicUi
import app.granger.player.track
import app.granger.readiness.Coverage
import app.granger.readiness.DomainCoverage
import app.granger.readiness.ReadinessReport
import app.granger.readiness.StorageRow
import app.granger.readiness.Source
import app.granger.search.SearchScreen
import app.granger.search.SearchViewModel
import app.granger.settings.SettingsScreen
import app.granger.settings.SettingsViewModel
import app.granger.shell.RailEntry
import app.granger.shell.RailTabUi
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The main screens under the en-XA pseudo-locale (Plan L1 Task 9): every string resource reads accented and bracketed
 * there ("[Çåñçéļ one two]"), so a node of plain English is text that didn't come from a resource. The screens' own data
 * (titles, names, sizes) is plain on purpose and listed per screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rXA-w400dp-h1600dp")
class PseudoLocaleTest {
    @get:Rule val compose = createComposeRule()

    /** Every text and content description on screen, merged nodes and their children both. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** Plain English: ASCII with a word in it, that isn't the screen's own data, a name or a size, time or count. */
    private fun plainEnglish(text: String, data: Set<String>): Boolean =
        text.all { it.code < 128 } && WORD.containsMatchIn(text) && text.trim() !in data + NAMES && !SIZE.matches(text.trim())

    private fun check(screen: String, data: Set<String>, content: @Composable () -> Unit) {
        compose.setContent { GrangerTheme { content() } }
        compose.waitForIdle()
        val all = texts()
        assertWithMessage("$screen shows no text").that(all).isNotEmpty()
        assertWithMessage("$screen: text that isn't a resource (en-XA shows it as plain English)")
            .that(all.filter { plainEnglish(it, data) }.distinct()).isEmpty()
    }

    private val owls = RailEntry.Tab(RailTabUi(1L, "Owls", UiText.Raw("Birds of the world"), 'O', available = true, current = false), at = 5L)
    private val blue = RailEntry.Play("album:Blue", "Blue", at = 1L)

    @Test fun home() {
        val report = ReadinessReport(
            domains = listOf(DomainCoverage("medicine", "Medicine", Coverage.BASIC, 0, 100, 400, emptyList())),
            signals = emptyList(),
            storage = listOf(StorageRow(Source.KIWIX, 11_100_000_000, 40)),
            needsCatalogRefresh = false,
        )
        val feed = FeedUi(
            HomeFeed(1, "family:wikipedia", FeedKind.ON_THIS_DAY, "", 0, 0), UiText.Raw("Wikipedia"), UiText.Raw("October 26"),
            Drawn(listOf(Card("The Football Association is founded", "Events", CardTarget.Article("u1", "October_26", "Events")))),
        )
        check(
            "Home",
            setOf("Owls", "Birds of the world", "Blue", "Medicine", "Wikipedia", "October 26", "The Football Association is founded", "Events", "O", "B"),
        ) {
            HomeContent(HomeUi(strip = report, continueCards = listOf(owls, blue), feeds = listOf(feed)), onReadiness = {}, onOpenTab = {}, onPlay = {})
        }
    }

    @Test fun library() {
        val classics = CollectionRow(ShelfCollection("c:gc", "Granger Classics", "literature", false, defaultKey = "gc"), emptyList(), books = List(3) { book("b$it") })
        val mine = CollectionRow(ShelfCollection("c:mine", "My books", "literature", false, defaultKey = "mine"), emptyList(), books = listOf(book("m")))
        check("Library", setOf("Granger Classics", "My books")) {
            LibraryContent(
                LibraryUi(libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(classics, mine)))), false),
                SnackbarHostState(), {}, {}, {}, downloadsBadge = 2, onDownloads = {}, onGetMore = {}, onImport = {},
            )
        }
    }

    @Test fun search() {
        val search = object : LibrarySearch {
            override suspend fun titles(query: String) = TitleResults((1..3).map { SearchHit("p", "Pack", "T$it", "Title $it") })
            override suspend fun fullText(query: String) = emptyList<SearchHit>()
        }
        val vm = SearchViewModel(search, FakeReadingSessions(), "title")
        check("Search", setOf("title", "Pack", "Title 1", "Title 2", "Title 3")) {
            SearchScreen(
                vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {},
                onPlaySong = {}, onBack = {},
            )
        }
    }

    @Test fun settings() {
        val waiver = object : ArchivePrefs {
            override val waiverAccepted: StateFlow<Boolean> = MutableStateFlow(false)
            override fun accept() {}
            override fun withdraw() {}
        }
        check("Settings", setOf("Internal storage", "Nanny")) {
            SettingsScreen(
                SettingsViewModel(
                    FakeDownloads(), FakeVolumes(listOf(volume("/internal", "Internal storage"))), FakePrefs(), waiver, FakeAppearancePrefs(),
                    appScope = CoroutineScope(Dispatchers.Unconfined),
                ),
                onModels = {}, onSetUpAgain = {}, onBack = {}, onVoice = {}, voiceSummary = "Nanny", onMediaSettings = {}, audiobooks = true,
            )
        }
    }

    @Test fun player() {
        check("Player", setOf("Song A", "Artist", "Album")) {
            ListenPlayerScreen(PlayerViewModel(FakePlayerHub(musicUi(track("a", "Song A")))), onClose = {})
        }
    }

    private companion object {
        val WORD = Regex("[A-Za-z]{2,}")
        /** Names the same in every language, kept `translatable="false"`: the pseudo-locale leaves them plain. */
        val NAMES = setOf("Granger")
        /** Sizes, times and counts the screens format themselves: "11 GB", "0:50", "3:20". */
        val SIZE = Regex("""[\d.,:\s%/·–-]*(B|KB|MB|GB|TB)?""")
    }
}
