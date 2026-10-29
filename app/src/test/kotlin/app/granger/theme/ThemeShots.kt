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
import app.granger.research.AnswerBody
import app.granger.R
import app.granger.browse.BrowseActions
import app.granger.browse.BrowseContent
import app.granger.browse.BrowseSection
import app.granger.browse.BrowseUi
import app.granger.browse.MAPS_SECTION
import app.granger.browse.OTHER_SECTION
import app.granger.browse.PackItem
import app.granger.library.KindRow
import app.granger.sources.AskReach
import app.granger.listen.CardKind
import app.granger.listen.ListenActions
import app.granger.listen.ListenContent
import app.granger.listen.ListenUi
import app.granger.listen.NowPlayingCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import app.granger.audiobooks.AudiobookCover
import app.granger.music.AlbumCover
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
import app.granger.player.ListenPlayerScreen
import app.granger.player.MiniPlayer
import app.granger.player.PlayerViewModel
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import app.granger.player.voiceUi
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

    /** An Ask answer with a heading, lists that wrap, code and citations (Plan U3c Decision 8). */
    @Test fun answer() = shoot("answer") {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            AnswerBody(
                "Owls hunt mostly at night, by **sound** as much as sight [1].\n\n## How they hunt\n\n" +
                    "1. They perch and listen, turning the head through as much as 270 degrees to place a sound [2].\n" +
                    "2. They glide down on soft-edged feathers that make almost no noise.\n" +
                    "  - The *barn owl* can strike in total darkness [3].\n" +
                    "  - Others need some light.\n" +
                    "10. They swallow small prey whole.\n\n" +
                    "The pellets they cough up (search `owl pellet`) show what they ate:\n" +
                    "- bones\n- fur and feathers, which the stomach can't break down and presses into a neat bundle [1, 2]",
                onCite = {},
            )
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

    /** The Library is books (Plan U3c Decision 2): the Kiwix folder here is Browse's, and doesn't show. */
    @Test fun library() = shoot("library") {
        val wikipedia = CollectionRow(ShelfCollection("c:kiwix", "Kiwix", "literature", false, defaultKey = "kiwix"), listOf(pack("w")))
        val classics = CollectionRow(ShelfCollection("c:gc", "Granger Classics", "literature", false, defaultKey = "gc"), emptyList(), books = List(12) { book("b$it") })
        val mine = CollectionRow(ShelfCollection("c:mine", "My books", "literature", false, defaultKey = "mine"), emptyList(), books = listOf(book("m")))
        val archive = CollectionRow(ShelfCollection("c:ao", "Archive.org", "literature", false, defaultKey = "ao"), emptyList(), books = List(3) { book("a$it") })
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            LibraryContent(
                LibraryUi(libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(archive, classics, wikipedia, mine)))), false),
                SnackbarHostState(), {}, {}, {}, downloadsBadge = 2, onDownloads = {}, onGetMore = {}, onImport = {},
            )
        }
    }

    /** Browse's home (Plan U3c Decision 1): each pack a row under its knowledge area, then Other, then the maps. */
    @Test fun browse() = shoot("browse") {
        val gb = 1_000_000_000L
        val medicine = listOf(
            PackItem(pack("med", "WikEM emergency medicine").copy(sizeBytes = 45_000_000), AskReach.KEYWORDS),
            PackItem(pack("wikimed", "WikiMed").copy(sizeBytes = 2 * gb), AskReach.MEANING),
        )
        val making = listOf(PackItem(pack("knots", "Knots (Wikipedia)").copy(sizeBytes = 30_000_000), AskReach.MEANING))
        val reference = listOf(
            PackItem(pack("wiki", "Wikipedia").copy(sizeBytes = 48 * gb), AskReach.MEANING),
            PackItem(pack("wikt", "Wiktionary").copy(sizeBytes = 8 * gb), AskReach.MEANING),
        )
        val travel = listOf(PackItem(pack("voy", "Wikivoyage travel guide").copy(sizeBytes = 900_000_000), AskReach.MEANING))
        val other = listOf(PackItem(pack("dev", "DevDocs: Kotlin").copy(sizeBytes = 20_000_000), AskReach.KEYWORDS))
        val maps = listOf(PackItem(pack("uk", "United Kingdom").copy(sizeBytes = 1_400_000_000)))
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.BROWSE,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            BrowseContent(
                BrowseUi(
                    listOf(
                        BrowseSection("medicine", "Medicine & first aid", medicine),
                        BrowseSection("making", "Making, repair & engineering", making),
                        BrowseSection("maps", "Maps, places & travel", travel),
                        BrowseSection("reference", "General reference", reference),
                        BrowseSection(OTHER_SECTION, "Other", other),
                        BrowseSection(MAPS_SECTION, "Maps", maps),
                    ),
                ),
                "/packs", downloadsBadge = 0, BrowseActions(),
            )
        }
    }

    @Test fun musicPlayer() = shoot("music") { ListenPlayerScreen(PlayerViewModel(FakePlayerHub(musicUi())), onClose = {}) }

    @Test fun readerPlayer() {
        val at = app.granger.core.speech.ReadAddress.Text("book:pan", 4, 1)
        val hub = FakePlayerHub(voiceUi(title = "Peter Pan", section = "Chapter 3")).apply {
            voiceProgress.value = app.granger.core.player.voice.VoiceProgress(
                at, app.granger.core.speech.Utterance(at, app.granger.core.speech.UtteranceKind.SENTENCE, "All children, except one, grow up.", 0, 350L), 4,
                previous = "Chapter 1: Peter Breaks Through.", next = "They soon know that they will grow up.",
            )
        }
        val voices = listOf(app.granger.core.speech.Voice(app.granger.core.speech.VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), "Granger voice (ljspeech)", "en-US"))
        shoot("reader") { ListenPlayerScreen(PlayerViewModel(hub), onClose = {}, voices = voices) }
    }

    @Test fun audiobookPlayer() {
        val book = audiobook("b", title = "Treasure Island", author = "Robert Louis Stevenson")
        val chapters = listOf(chapter(book, 0, 60_000L), chapter(book, 1, 60_000L))
        shoot("audiobook") {
            ListenPlayerScreen(PlayerViewModel(FakePlayerHub(bookUi(book, chapters[1], chapterCount = 2, positionMs = 20_000L)), chapters = { MutableStateFlow(chapters) }), onClose = {})
        }
    }

    /**
     * Missing art (Plan U3c Decision 6, W8): an album grid with no covers and an audiobook, the small tiles at the
     * sizes they're drawn (queue, Browse, mini player, search, Listen), the rail's tab, play, search and chat, and
     * the mini player, all on the themed tile.
     */
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    @Test fun tiles() = shoot("tiles") {
        val rail = RailUi(
            listOf(
                owls.copy(tab = owls.tab.copy(packId = "wiki", path = "A/Owl")), blue,
                RailEntry.Play("audiobook:t", "Treasure Island", at = 1L), RailEntry.Search("sextant", at = 3L), RailEntry.Chat(7L, "About owls", at = 2L),
            ),
        )
        GrangerShell(
            rail = rail, expanded = false, railVisible = true, selected = RailTool.LISTEN,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val albums = listOf("Kind of Blue", "A Love Supreme", "The Köln Concert", "Moanin'", "1984", "(What's the Story) Morning Glory?")
                    for (row in albums.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (title in row) Column(Modifier.weight(1f)) {
                            AlbumCover(null, title, Modifier.fillMaxWidth())
                            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AudiobookCover(null, "Treasure Island", Modifier.size(72.dp))
                        for ((size, kind) in listOf(40 to MediaKind.MUSIC, 40 to MediaKind.REFERENCE, 44 to MediaKind.VOICE, 48 to MediaKind.ARTIST, 56 to MediaKind.AUDIOBOOK)) {
                            Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp))) { MediaTile("Moby-Dick", kind) }
                        }
                    }
                }
                MiniPlayer(musicUi(app.granger.player.track("a", "Blue in Green", artists = "Miles Davis")), onPlayPause = {}, onSecondary = {}, onOpen = {})
            }
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
     * Listen's icon (headphones, picked over library_music and headphones over a phone) beside the other rail tools, one
     * PNG: Field on the left, Night on the right. Each column is the narrow rail's tools with Listen selected at the
     * foot, and the full rail's Listen row at 20 dp.
     */
    @Config(qualifiers = "w720dp-h520dp-xxhdpi")
    @Test fun listenIcons() {
        val candidates = listOf(
            "headphones" to R.drawable.ic_headphones,
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

    /**
     * Browse's icon (Plan U3c Decision 1): the globe (Material public, wired) beside the compass (explore), each in the
     * rail's order with the other tools, Browse selected at the foot and the full rail's Browse row at 20 dp. Field on
     * the left, Night on the right.
     */
    @Config(qualifiers = "w720dp-h560dp-xxhdpi")
    @Test fun browseIcons() {
        val candidates = listOf("globe" to R.drawable.ic_browse, "compass" to R.drawable.ic_browse_alt)
        compose.setContent {
            Row(Modifier.fillMaxSize()) {
                for (night in listOf(false, true)) GrangerTheme(night = night) {
                    Row(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                        for ((name, icon) in candidates) IconColumn(name, icon, Modifier.weight(1f), tool = "Browse")
                    }
                }
            }
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out).mkdirs()
        File(out, "browse-icons.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The readers' shared bars (Plan U3c Decision 4) over a stand-in page, at 360 dp: the wiki idle and reading aloud
     * (no playback buttons in the bar; the mini player above it has them), and an EPUB with its place and slider.
     */
    private fun wikiBars(voice: app.granger.player.VoiceButton) = app.granger.reader.wikiBarActions(
        packTitle = "Wikipedia", hasPack = true, hasPage = true, hasHome = true, bookmarked = false, onBack = {}, onListen = {}, voice = voice,
    )

    private val wikiPage = "Photosynthesis is a system of biological processes by which photosynthetic organisms, such as most plants, " +
        "algae and cyanobacteria, convert light energy, typically from sunlight, into the chemical energy necessary to fuel their metabolism."

    @Test fun barsWiki() = shoot("bars-wiki") { ReaderShot("Photosynthesis", wikiBars(app.granger.player.VoiceButton.IDLE), wikiPage) }

    @Test fun barsWikiReading() = shoot("bars-wiki-reading") {
        ReaderShot("Photosynthesis", wikiBars(app.granger.player.VoiceButton.READING), wikiPage) {
            MiniPlayer(app.granger.player.voiceUi("Photosynthesis", section = "Overview"), onPlayPause = {}, onSecondary = {}, onOpen = {})
        }
    }

    @Test fun barsEpub() {
        val locator = org.readium.r2.shared.publication.Locator(
            href = org.readium.r2.shared.util.Url("c1.xhtml")!!, mediaType = org.readium.r2.shared.util.mediatype.MediaType.XHTML,
            title = "Chapter 1", locations = org.readium.r2.shared.publication.Locator.Locations(position = 12, totalProgression = 0.04),
        )
        val book = app.granger.ebook.bookBarActions(isPdf = false, bookmarked = true, onBack = {}, onListen = {})
        shoot("bars-epub") {
            ReaderShot(
                "Pride and Prejudice", book,
                "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                progress = { app.granger.ebook.BookProgress(locator, 410) {} },
            )
        }
    }

    /** A reader's bars over [text]; [mini], the mini player the shell draws over the page, above the top bar. */
    @Composable
    private fun ReaderShot(
        title: String, actions: app.granger.reader.ReaderBarActions, text: String,
        progress: (@Composable () -> Unit)? = null, mini: (@Composable () -> Unit)? = null,
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            mini?.invoke()
            app.granger.reader.ReaderTopBar(title, actions)
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            app.granger.reader.ReaderBottomBar(actions, progress = progress)
        }
    }

    /** One candidate's column: the rail's tools with [icon] as [tool] (Listen or Browse), that tool selected, its full-rail row. */
    @Composable
    private fun IconColumn(name: String, icon: Int, modifier: Modifier, tool: String = "Listen") {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val listen = if (tool == "Listen") icon else R.drawable.ic_headphones
        val tools = listOfNotNull(
            "Home" to painterResource(R.drawable.ic_owl),
            "Search" to rememberVectorPainter(Icons.Filled.Search),
            "Ask" to painterResource(R.drawable.ic_research),
            if (tool == "Browse") "Browse" to painterResource(icon) else null,
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
            IconButton(onClick = {}) { Icon(painterResource(icon), contentDescription = tool, tint = MaterialTheme.colorScheme.primary) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                Text(tool, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
