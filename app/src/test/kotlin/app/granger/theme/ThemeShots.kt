package app.granger.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.library.KindRow
import app.granger.listen.CardKind
import app.granger.listen.ListenActions
import app.granger.listen.ListenContent
import app.granger.listen.ListenUi
import app.granger.listen.NowPlayingCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.granger.book
import app.granger.core.library.CollectionRow
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.home.Card
import app.granger.home.CardTarget
import app.granger.home.Drawn
import app.granger.home.FeedUi
import app.granger.home.HomeContent
import app.granger.home.HomeUi
import app.granger.library.LibraryContent
import app.granger.library.LibraryUi
import app.granger.library.libraryKinds
import app.granger.onboarding.OnboardingActions
import app.granger.onboarding.OnboardingContent
import app.granger.onboarding.OnboardingUi
import app.granger.onboarding.Step
import app.granger.pack
import app.granger.player.FakePlayerHub
import app.granger.player.MiniPlayer
import app.granger.player.PlayerScreen
import app.granger.player.PlayerViewModel
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import app.granger.readiness.Coverage
import app.granger.readiness.DomainCoverage
import app.granger.readiness.ReadinessReport
import app.granger.readiness.Signal
import app.granger.readiness.Source
import app.granger.readiness.StorageRow
import app.granger.shell.GrangerShell
import app.granger.shell.RailEntry
import app.granger.shell.RailEntryActions
import app.granger.shell.RailTabUi
import app.granger.shell.RailTool
import app.granger.shell.RailUi
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screens in Field and Night as PNGs under app/build/theme-shots (Plan T1, Decision 8). A dev tool for looking
 * at the theme, skipped unless Gradle runs with -PthemeShots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ThemeShots {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("granger.themeShots").orEmpty()

    @Before fun onlyWhenAsked() = assumeTrue(out.isNotEmpty())

    private fun shoot(name: String, content: @Composable () -> Unit) {
        var night by mutableStateOf(true)
        compose.setContent { GrangerTheme(night = night) { content() } }
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val ids = listOf("medicine", "food", "making", "science", "technology", "maps", "reference", "literature", "thought")
    private val report = ReadinessReport(
        domains = ids.mapIndexed { i, id -> DomainCoverage(id, id, if (i < 3) Coverage.BASIC else Coverage.NONE, 0, 100, 400, emptyList()) },
        signals = listOf(Signal.Unsearchable("p", "Owls can't be searched: the pack has no index.")),
        storage = listOf(StorageRow(Source.KIWIX, 11_100_000_000, 40)),
        needsCatalogRefresh = false,
    )
    private val owls = RailEntry.Tab(RailTabUi(1L, "Owls", "Birds of the world", 'O', available = true, current = true), at = 5L, pinned = true)
    private val blue = RailEntry.Play("album:Blue", "Blue", at = 1L)
    private val dayFeed = FeedUi(
        HomeFeed(1, "family:wikipedia", FeedKind.ON_THIS_DAY, "", 0, 0), "Wikipedia",
        Drawn("October 26", listOf(Card("1863 – The Football Association is founded in London.", "Events", CardTarget.Article("u1", "October_26", "Events")))),
    )

    @Test fun home() = shoot("home") {
        GrangerShell(
            rail = RailUi(listOf(owls, RailEntry.Search("sextant", at = 3L), blue)), expanded = false, railVisible = true, selected = null,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            HomeContent(HomeUi(strip = report, continueCards = listOf(owls, blue), feeds = listOf(dayFeed)), onReadiness = {}, onOpenTab = {}, onPlay = {})
        }
    }

    @Test fun railExpanded() = shoot("rail") {
        GrangerShell(
            rail = RailUi(listOf(owls, RailEntry.Search("sextant", at = 3L), RailEntry.Chat(7L, "About owls", at = 2L), blue)),
            expanded = true, railVisible = true, selected = null,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) { Box(Modifier.fillMaxSize()) }
    }

    @Test fun library() = shoot("library") {
        val wikipedia = CollectionRow(ShelfCollection("c:kiwix", "Kiwix", "literature", false, defaultKey = "kiwix"), listOf(pack("w")))
        val classics = CollectionRow(ShelfCollection("c:gc", "Granger Classics", "literature", false, defaultKey = "gc"), emptyList(), books = listOf(book("b")))
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            LibraryContent(
                LibraryUi(libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(classics, wikipedia)))), false),
                "/packs", SnackbarHostState(), {}, {}, {}, downloadsBadge = 2, onDownloads = {}, onGetMore = {}, onImport = {},
            )
        }
    }

    @Test fun musicPlayer() = shoot("music") { PlayerScreen(PlayerViewModel(FakePlayerHub(musicUi())), onClose = {}) }

    @Test fun audiobookPlayer() {
        val book = audiobook("b", title = "Treasure Island", author = "Robert Louis Stevenson")
        val chapters = listOf(chapter(book, 0, 60_000L), chapter(book, 1, 60_000L))
        shoot("audiobook") {
            PlayerScreen(PlayerViewModel(FakePlayerHub(bookUi(book, chapters[1], chapterCount = 2, positionMs = 20_000L)), chapters = { MutableStateFlow(chapters) }), onClose = {})
        }
    }

    @Test fun miniPlayer() = shoot("mini") { MiniPlayer(musicUi(), onPlayPause = {}, onSecondary = {}, onOpen = {}) }

    @Test fun welcome() = shoot("welcome") { OnboardingContent(OnboardingUi(step = Step.WELCOME), OnboardingActions()) }

    @Test fun listen() = shoot("listen") {
        val phone = CollectionRow(ShelfCollection("c:phone", "Phone", "music", false, defaultKey = "phone"), emptyList(), albums = emptyList())
        val librivox = CollectionRow(ShelfCollection("c:lv", "LibriVox", "audiobooks", false, defaultKey = "lv"), emptyList(), audiobooks = listOf(audiobook("t")))
        val card = NowPlayingCard("Now playing", "Blue in Green", "Miles Davis", null, CardKind.MUSIC, live = true, playing = true)
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LISTEN,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            ListenContent(
                ListenUi(card, listOf(KindRow("music", "Music", "music", listOf(phone)), KindRow("audiobooks", "Audiobooks", "audiobooks", listOf(librivox)))),
                ListenActions(),
            )
        }
    }

    @Test fun listenEmpty() = shoot("listen-empty") {
        GrangerShell(
            rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LISTEN,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) { ListenContent(ListenUi(null, emptyList()), ListenActions()) }
    }

    /**
     * Listen's icon beside the other rail tools (Plan U2 Task 3), one PNG: Field on the left, Night on the right, and in
     * each the wired library_music, then the two candidates (headphones over a phone, headphones). Each column is the
     * narrow rail's tools with Listen selected at the foot, and the full rail's Listen row at 20 dp.
     */
    @Config(qualifiers = "w720dp-h520dp-xxhdpi")
    @Test fun listenIcons() {
        val candidates = listOf(
            "library_music" to R.drawable.ic_listen,
            "device" to R.drawable.ic_listen_alt_device,
            "headphones" to R.drawable.ic_listen_alt_headphones,
        )
        compose.setContent {
            Row(Modifier.fillMaxSize()) {
                for (night in listOf(false, true)) GrangerTheme(night = night) {
                    Row(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                        for ((name, icon) in candidates) IconColumn(name, icon, Modifier.weight(1f))
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out).mkdirs()
        File(out, "listen-icons.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun IconColumn(name: String, listen: Int, modifier: Modifier) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val tools = listOf(
            "Home" to painterResource(R.drawable.ic_owl),
            "Search" to rememberVectorPainter(Icons.Filled.Search),
            "Ask" to painterResource(R.drawable.ic_research),
            "Library" to painterResource(R.drawable.ic_library_books_open),
            "Listen" to painterResource(listen),
            "Settings" to rememberVectorPainter(Icons.Filled.Settings),
        )
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            for ((label, painter) in tools) {
                IconButton(onClick = {}) { Icon(painter, contentDescription = label, tint = muted) }
            }
            // Selected, as the rail draws the tool you're in.
            IconButton(onClick = {}) { Icon(painterResource(listen), contentDescription = "Listen", tint = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(listen), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                Text("Listen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
