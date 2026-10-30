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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.aspectRatio
import app.granger.core.library.ShelfKey
import app.granger.library.shelf.ShelfGrid
import app.granger.library.shelf.ShelfHeader
import app.granger.library.shelf.ShelfList
import app.granger.library.shelf.ShelfRow
import app.granger.library.shelf.ShelfSections
import app.granger.library.shelf.ShelfView
import app.granger.library.shelf.ViewToggle
import app.granger.library.shelf.sortedByShelfKey
import app.granger.settings.RailSide
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
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

    /** Plan DL Task 4: Home with the live downloads card, running and stopped. */
    private fun homeWith(card: app.granger.home.DownloadsCard) = shoot(if (card is app.granger.home.DownloadsCard.Stopped) "home-downloads-stopped" else "home-downloads") {
        GrangerShell(
            rail = RailUi(listOf(owls, RailEntry.Search("sextant", at = 3L), blue)), expanded = false, railVisible = true, selected = null,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            HomeContent(
                HomeUi(strip = report, continueCards = listOf(owls, blue), feeds = listOf(dayFeed), downloads = card),
                onReadiness = {}, onOpenTab = {}, onPlay = {},
            )
        }
    }

    @Test fun homeDownloads() = homeWith(
        app.granger.home.DownloadsCard.Active("Wikipedia: best articles", false, 3, 14, 1_200_000_000, 9_800_000_000, 10, 25 * 60_000L),
    )

    @Test fun homeDownloadsStopped() = homeWith(app.granger.home.DownloadsCard.Stopped(2, listOf("a"), setOf("a@x", "b@x")))

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
        val voices = listOf(
            app.granger.core.speech.Voice(app.granger.core.speech.VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), "Granger voice (ljspeech)", "en-US"),
            app.granger.core.speech.Voice(app.granger.core.speech.VoiceId.Granger("vits-piper-en_GB-granger_professor-medium"), "Granger voice (professor)", "en-GB"),
        ) + (1..6).map { i ->
            app.granger.core.speech.Voice(app.granger.core.speech.VoiceId.System("com.google.android.tts", "en-us-x-io$i-local"), "System voice io$i (en-US)", "en-US")
        }
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

    /** Sixty classics, for the shared shelf's shots (Plan LV Task 2). */
    private val classics = listOf(
        "The Adventures of Tom Sawyer" to "Mark Twain", "Aesop's Fables" to "Aesop", "Anna Karenina" to "Leo Tolstoy",
        "Around the World in Eighty Days" to "Jules Verne", "The Awakening" to "Kate Chopin", "Beowulf" to "",
        "Bleak House" to "Charles Dickens", "The Brothers Karamazov" to "Fyodor Dostoevsky", "Candide" to "Voltaire",
        "The Call of the Wild" to "Jack London", "A Christmas Carol" to "Charles Dickens", "Crime and Punishment" to "Fyodor Dostoevsky",
        "David Copperfield" to "Charles Dickens", "Don Quixote" to "Miguel de Cervantes", "Dracula" to "Bram Stoker",
        "Emma" to "Jane Austen", "Émile" to "Jean-Jacques Rousseau", "Frankenstein" to "Mary Shelley",
        "Great Expectations" to "Charles Dickens", "Gulliver's Travels" to "Jonathan Swift", "Hamlet" to "William Shakespeare",
        "Heart of Darkness" to "Joseph Conrad", "The Iliad" to "Homer", "The Importance of Being Earnest" to "Oscar Wilde",
        "Ivanhoe" to "Walter Scott", "Jane Eyre" to "Charlotte Brontë", "The Jungle Book" to "Rudyard Kipling",
        "Kidnapped" to "Robert Louis Stevenson", "King Lear" to "William Shakespeare", "Leviathan" to "Thomas Hobbes",
        "Little Women" to "Louisa May Alcott", "Madame Bovary" to "Gustave Flaubert", "Middlemarch" to "George Eliot",
        "Moby-Dick" to "Herman Melville", "Narrative of the Life of Frederick Douglass" to "Frederick Douglass",
        "North and South" to "Elizabeth Gaskell", "The Odyssey" to "Homer", "Oliver Twist" to "Charles Dickens",
        "Paradise Lost" to "John Milton", "Persuasion" to "Jane Austen", "The Picture of Dorian Gray" to "Oscar Wilde",
        "Pride and Prejudice" to "Jane Austen", "The Prince" to "Niccolò Machiavelli", "Robinson Crusoe" to "Daniel Defoe",
        "The Scarlet Letter" to "Nathaniel Hawthorne", "Sense and Sensibility" to "Jane Austen", "Siddhartha" to "Hermann Hesse",
        "A Tale of Two Cities" to "Charles Dickens", "The Time Machine" to "H. G. Wells", "Treasure Island" to "Robert Louis Stevenson",
        "Ulysses" to "James Joyce", "Utopia" to "Thomas More", "Vanity Fair" to "William Makepeace Thackeray",
        "The War of the Worlds" to "H. G. Wells", "Walden" to "Henry David Thoreau", "Wuthering Heights" to "Emily Brontë",
        "The Yellow Wallpaper" to "Charlotte Perkins Gilman", "Zadig" to "Voltaire", "1984" to "George Orwell",
        "20,000 Leagues Under the Sea" to "Jules Verne",
    )

    private fun classicsShelf() = ShelfSections.of(
        classics.sortedByShelfKey({ ShelfKey.of(it.first) }), byName = true, key = { it.first },
        letter = { ShelfKey.letter(ShelfKey.of(it.first)) },
    )

    /** The shared shelf in a collection's frame: the rail on [side], a top bar with the view toggle, a find field. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun ShelfShot(view: ShelfView, side: RailSide = RailSide.LEFT) {
        GrangerShell(
            rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, side = side,
        ) {
            androidx.compose.material3.Scaffold(
                topBar = {
                    androidx.compose.material3.TopAppBar(
                        title = { Text("Granger Classics") },
                        actions = { ViewToggle(view) {} },
                    )
                },
            ) { padding ->
                val shelf = classicsShelf()
                val find: () -> Unit = {}
                if (view == ShelfView.LIST) {
                    ShelfList(shelf, Modifier.padding(padding).fillMaxSize(), header = {
                        item { ShelfHeader("Title", listOf("Title", "Author", "Recently read"), { it }, {}) }
                    }) { (title, author) ->
                        ShelfRow(
                            title, author, cover = { Box(Modifier.size(32.dp, 48.dp).clip(RoundedCornerShape(4.dp))) { MediaTile(title, MediaKind.BOOK) } },
                            onClick = find,
                            detail = { Text("EPUB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    }
                } else {
                    ShelfGrid(
                        shelf, cellMin = 104.dp, modifier = Modifier.padding(padding).fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) { (title, author) ->
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))) { MediaTile(title, MediaKind.BOOK) }
                            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, minLines = 2, color = MaterialTheme.colorScheme.onSurface)
                            Text(author, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    @Test fun shelfList() = shoot("shelf-list") { ShelfShot(ShelfView.LIST) }

    @Test fun shelfGrid() = shoot("shelf-grid") { ShelfShot(ShelfView.GRID) }

    @Test fun shelfListRailRight() = shoot("shelf-list-rail-right") { ShelfShot(ShelfView.LIST, RailSide.RIGHT) }

    /** Mid-drag: the bubble beside the thumb, the list at M. */
    @Test fun shelfDrag() {
        var night by mutableStateOf(true)
        compose.setContent { GrangerTheme(night = night) { ShelfShot(ShelfView.LIST) } }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Jump to A")).performTouchInput { down(center) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Jump to A")).performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, height * 11.5f)) }
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "shelf-drag-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Classics as the collection screen shows it, at 360 dp: the find field, chips and toggle with the list or grid. */
    private fun booksShot(name: String, view: ShelfView) {
        val shelves = app.granger.FakeShelves()
        val books = classics.mapIndexed { i, (title, author) -> app.granger.book("c$i", title, author = author) }
        shelves.shelfList.value = listOf(
            app.granger.core.library.Shelf(
                MediaType("lit", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:gc", "Granger Classics", "lit", false, defaultKey = "gc"), emptyList(), books)),
            ),
        )
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.BOOKS, view) }
        val catalog = app.granger.FakeCatalogSource(
            app.granger.catalogOf(books = books.mapIndexed { i, b -> app.granger.bookEntry(b.sourceRef, listOf("Novels & stories", "Drama", "Philosophy")[i % 3]) }),
        )
        val vm = app.granger.library.CollectionViewModel(
            "c:gc", shelves, app.granger.FakeLibrary(), app.granger.FakeReadingSessions(), app.granger.FakeBookshelf(), app.granger.FakeDownloads(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), catalog = catalog, viewPrefs = prefs,
            compute = kotlinx.coroutines.Dispatchers.Main,
        )
        shoot(name) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.library.CollectionScreen(vm, onOpened = {}, onOpenPack = {}, onGetBooks = {}, onOpenCollection = {}, onBack = {}) }
        }
    }

    /** Real titles from the Music pack, the first four searchable in full. */
    private val musicBooks = listOf(
        "Beethoven" to "Ludwig Nohl", "The Art of the Fugue" to "Johann Sebastian Bach", "Chopin and Other Musical Essays" to "Henry T. Finck",
        "Great Pianists on Piano Playing" to "James Francis Cooke", "The Music of the Wild" to "Gene Stratton-Porter",
        "A History of Music" to "Waldo Selden Pratt", "How to Listen to Music" to "Henry Edward Krehbiel",
        "Music: An Art and a Language" to "Walter Raymond Spalding", "Mozart: The Man and His Works" to "W. J. Turner",
        "The Orchestra and Orchestral Music" to "W. J. Henderson", "Piano Playing, with Piano Questions Answered" to "Josef Hofmann",
        "The Standard Operaglass" to "Charles Annesley", "Wagner's Tristan and Isolde" to "Richard Wagner", "Bach" to "C. F. Abdy Williams",
    )

    /** A Gutenberg pack's books as their collection (Plan GB), at 360 dp: header, marks, pack covers for some. */
    private fun gutenbergShot(name: String, view: ShelfView) {
        val coverDir = File(System.getProperty("java.io.tmpdir"), "gb-covers").apply { mkdirs() }
        val books = musicBooks.mapIndexed { i, (title, author) ->
            app.granger.book("book:gutenberg:${1000 + i}", title, author = author).copy(
                origin = app.granger.core.library.BookOrigin.GUTENBERG,
                sourceRef = "gutenberg_en_lcc-m/$title.${1000 + i}.epub", indexedAt = if (i in setOf(0, 1, 5, 9)) 5L else null,
            )
        }
        val covers = books.filterIndexed { i, _ -> i % 3 != 2 }.associate { book ->
            val file = File(coverDir, "${book.id.substringAfterLast(':')}.png")
            val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.rgb(90 + (book.title.length * 7) % 120, 70, 60))
            canvas.drawRect(20f, 40f, 180f, 120f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(230, 220, 190) })
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            book.id to file.path
        }
        val shelves = app.granger.FakeShelves()
        shelves.shelfList.value = listOf(
            app.granger.core.library.Shelf(
                MediaType("literature", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:gutenberg-m", "Music and books on music", "literature", false, defaultKey = "gutenberg-m"), emptyList(), books)),
            ),
        )
        val library = app.granger.FakeLibrary().apply {
            packList.value = listOf(pack("u-m", "Music and books on music · Project Gutenberg").copy(name = "gutenberg_en_lcc-m"))
        }
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.BOOKS, view) }
        val vm = app.granger.library.CollectionViewModel(
            "c:gutenberg-m", shelves, library, app.granger.FakeReadingSessions(), app.granger.FakeBookshelf().apply { positionMap.value = mapOf(books[1].id to app.granger.core.library.BookPosition(books[1].id, "{}", 0.4, "", 1L)) }, app.granger.FakeDownloads(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), viewPrefs = prefs,
            compute = kotlinx.coroutines.Dispatchers.Main,
        )
        shoot(name) {
            androidx.compose.runtime.CompositionLocalProvider(app.granger.library.LocalPackCovers provides { book -> covers[book.id] }) {
                GrangerShell(
                    rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                    onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                ) { app.granger.library.CollectionScreen(vm, onOpened = {}, onOpenPack = {}, onGetBooks = {}, onOpenCollection = {}, onBack = {}) }
            }
        }
    }

    /** Food for Preppers as its collection (Plan KB Decision 7), at 360 dp: PDFs, one opened with a first-page cover, a scan. */
    private fun kiwixBooksShot(name: String, view: ShelfView) {
        val coverDir = File(System.getProperty("java.io.tmpdir"), "kb-covers").apply { mkdirs() }
        val docs = listOf(
            "Philmont Trail Cookbook" to "Various", "Camping Recipes" to "Various", "Dutch Oven Cooking" to "Various",
            "Food Storage in the Home" to "Utah State University", "Home Canning Guide" to "USDA",
            "Drying Foods" to "University of Georgia", "Making Jerky at Home Safely" to "USDA",
            "Bread Baking Basics" to "Various", "Root Cellaring" to "Various", "Emergency Food Storage" to "FEMA",
        )
        val books = docs.mapIndexed { i, (title, author) ->
            app.granger.book("book:nautilus:zimgit-food-preparation_en/Food Preparation (${i + 1}).pdf", title, author = author).copy(
                origin = app.granger.core.library.BookOrigin.NAUTILUS,
                sourceRef = "zimgit-food-preparation_en/files/Food Preparation (${i + 1}).pdf",
                format = app.granger.core.library.BookFormat.PDF,
                indexedAt = if (i in setOf(0, 1, 3)) 5L else null, searchable = i != 3,
            )
        }.mapIndexed { i, book ->
            if (i !in setOf(0, 1, 3)) return@mapIndexed book
            val file = File(coverDir, "$i.png")
            val bmp = Bitmap.createBitmap(200, 260, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val ink = android.graphics.Paint().apply { color = android.graphics.Color.rgb(40, 60, 40) }
            canvas.drawRect(16f, 20f, 184f, 60f, ink)
            for (line in 0 until 9) canvas.drawRect(16f, 80f + line * 18f, 120f + (line * 23) % 60, 88f + line * 18f, ink)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            book.copy(coverPath = file.path)
        }
        val key = "kiwixbooks-zimgit-food-preparation_en"
        val shelves = app.granger.FakeShelves()
        shelves.shelfList.value = listOf(
            app.granger.core.library.Shelf(
                MediaType("literature", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:$key", "Food for Preppers", "literature", false, defaultKey = key), emptyList(), books)),
            ),
        )
        val library = app.granger.FakeLibrary().apply {
            packList.value = listOf(pack("u-food", "Food for Preppers").copy(name = "zimgit-food-preparation_en", scraper = "nautiluszim 1.1.1"))
        }
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.BOOKS, view) }
        val vm = app.granger.library.CollectionViewModel(
            "c:$key", shelves, library, app.granger.FakeReadingSessions(), app.granger.FakeBookshelf().apply { positionMap.value = mapOf(books[1].id to app.granger.core.library.BookPosition(books[1].id, "{}", 0.4, "", 1L)) }, app.granger.FakeDownloads(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), viewPrefs = prefs,
            compute = kotlinx.coroutines.Dispatchers.Main,
        )
        shoot(name) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.library.CollectionScreen(vm, onOpened = {}, onOpenPack = {}, onGetBooks = {}, onOpenCollection = {}, onBack = {}) }
        }
    }

    /** A dialog alone, as its window draws it, in Night then Field. */
    private fun shootDialog(name: String, content: @Composable () -> Unit) {
        var night by mutableStateOf(true)
        compose.setContent { GrangerTheme(night = night) { content() } }
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onNode(androidx.compose.ui.test.isDialog()).captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /**
     * The Kiwix list's rows in plain words (Plan KB Decisions 8 and 9, walkthrough layout): a pack with flavours as radio
     * rows, one with a flavour installed and another chosen (Switch), a plain one kept compact.
     */
    @Test fun kiwixRows() = shoot("kiwix-rows") {
        val flavours = listOf(
            app.granger.sources.FlavourChoice("top_maxi", "With pictures", 6_990_000_000, chosen = true),
            app.granger.sources.FlavourChoice("top_nopic", "No pictures", 2_750_000_000, chosen = false),
            app.granger.sources.FlavourChoice("top_mini", "Mini (intro paragraphs only)", 300_000_000, chosen = false),
        )
        val rows = listOf(
            app.granger.sources.ItemRow(
                "top_maxi", "Wikipedia: best articles", "English · Updated October 2026 · CC BY-SA",
                "The 50,000 best Wikipedia articles", 6_990_000_000, app.granger.sources.ItemStatus.NotInstalled, null,
                flavours = flavours, license = "CC BY-SA 4.0",
            ),
            app.granger.sources.ItemRow(
                "med_maxi", "WikiMed", "English · Updated October 2026 · CC BY-SA", "The largest medical encyclopedia, from Wikipedia",
                1_860_000_000, app.granger.sources.ItemStatus.NotInstalled, "u-med",
                flavours = listOf(
                    app.granger.sources.FlavourChoice("med_maxi", "With pictures", 1_860_000_000, chosen = true),
                    app.granger.sources.FlavourChoice("med_nopic", "No pictures", 1_050_000_000, chosen = false, installed = true),
                ),
                installedFlavour = "no pictures", switches = true, license = "CC BY-SA 4.0",
            ),
            app.granger.sources.ItemRow(
                "survivor", "Survivor Library", "English · Updated September 2026 · Licence not stated",
                "Books and manuals on how things were done before electricity", 220_350_000_000,
                app.granger.sources.ItemStatus.NotInstalled, null, license = "Not stated by the source",
            ),
        )
        androidx.compose.material3.Surface {
            androidx.compose.foundation.layout.Column {
                for (row in rows) {
                    app.granger.sources.CatalogItemRow(row, onGet = {}, onCancel = {}, onRetry = {}, onRemove = {})
                    androidx.compose.material3.HorizontalDivider()
                }
            }
        }
    }

    /** Switch says what it trades (Plan KB walkthrough 2). */
    @Test fun switchFlavour() = shootDialog("switch-flavour") {
        app.granger.sources.SwitchDialog(
            "WikiMed",
            app.granger.sources.FlavourChoice("med_nopic", "No pictures", 1_050_000_000, chosen = false, installed = true),
            app.granger.sources.FlavourChoice("med_maxi", "With pictures", 1_860_000_000, chosen = true),
            onSwitch = {}, onDismiss = {},
        )
    }

    /** "Survivor Library is 220 GB" and Wikipedia's offer of its smaller flavour (Plan KB Decision 9). */
    @Test fun sizeCheck() = shootDialog("size-check") {
        app.granger.sources.SizeCheckDialog(
            app.granger.sources.SizeCheck(
                "wikipedia_en_all_maxi", "Wikipedia", 115_000_000_000, 41_000_000_000, "Phone",
                smaller = app.granger.sources.FlavourChoice("wikipedia_en_all_nopic", "No pictures", 48_000_000_000, chosen = false),
            ),
            onGet = {}, onDismiss = {},
        )
    }

    /** The Kiwix list (Plan KB Decisions 8 and 10), or one folded [family]'s A–Z list, from a catalog in the shape of Kiwix's. */
    private fun kiwixListShot(name: String, family: String?, query: String = "") {
        fun entry(id: String, title: String, category: String, family: String, size: Long, flavour: String = "", zim: String = id, featured: Boolean = false) =
            app.granger.packEntry(id, category = category, size = size, urls = listOf("https://download.kiwix.org/zim/$id.zim"), zimName = zim, featured = featured)
                .copy(title = title, family = family, flavour = flavour, snapshotDate = "2026-10-10")
        val docs = listOf("Angular", "Bash", "C", "C++", "CSS", "Django", "Docker", "Git", "Go", "HTML", "JavaScript", "Kotlin", "Node.js",
            "PostgreSQL", "Python", "React", "Rust", "Sass", "SQLite", "Vue")
            .flatMap { lang -> (0 until 3).map { i -> lang + listOf("", " 2", " 3")[i] } }
        val packs = listOf(
            entry("wikipedia_en_medicine_maxi", "WikiMed", "Medical", "wikipedia", 1_860_000_000, "maxi", "wikipedia_en_medicine", featured = true),
            entry("wikipedia_en_medicine_nopic", "WikiMed (no pictures)", "Medical", "wikipedia", 1_050_000_000, "nopic", "wikipedia_en_medicine", featured = true),
            entry("wikipedia_en_medicine_mini", "WikiMed Medical Encyclopedia", "Medical", "wikipedia", 170_000_000, "mini", "wikipedia_en_medicine"),
            entry("mdwiki_en_all", "MDWiki", "Medical", "mdwiki", 2_300_000_000),
            entry("freecodecamp_en_javascript", "freeCodeCamp JavaScript", "Programming", "freecodecamp", 9_000_000),
            entry("cooking.stackexchange.com_en_all", "Cooking Stack Exchange", "Q&A", "stack_exchange", 230_000_000),
            entry("diy.stackexchange.com_en_all", "DIY Stack Exchange", "Q&A", "stack_exchange", 900_000_000),
            entry("maps_en_france", "France", "Maps", "maps", 3_000_000_000),
            entry("maps_en_spain", "Spain", "Maps", "maps", 1_200_000_000),
        ) + docs.map { entry("devdocs_en_${it.lowercase().replace(" ", "-")}", "$it Docs", "Programming", "devdocs", 3_000_000) }
        val vm = app.granger.sources.SourceViewModel(
            app.granger.sources.CatalogScope.KIWIX, app.granger.FakeCatalogSource(app.granger.catalogOf(packs)), app.granger.FakeDownloads(),
            app.granger.FakeLibrary(), app.granger.FakeBookshelf(),
            app.granger.FakeVolumes(listOf(app.granger.volume("/internal", "Phone", free = 41_000_000_000))), app.granger.FakePrefs(),
            app.granger.core.ai.DeviceTier.ASK, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            io = kotlinx.coroutines.Dispatchers.Main, family = family,
        )
        if (query.isNotEmpty()) vm.setQuery(query)
        shoot(name) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.BROWSE,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.sources.SourceScreen(vm, title = "Kiwix", onBack = {}) }
        }
    }

    // --- Plan WF: Wikipedia as one family, over the published catalog ---

    private fun publishedCatalog(): app.granger.core.catalog.Catalog {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "catalog/catalog.json").isFile) dir = dir.parentFile
        val root = dir ?: error("catalog/catalog.json not found")
        val key = java.util.Base64.getDecoder().decode(File(root, "catalog/public-key.txt").readText().trim())
        val parsed = app.granger.core.catalog.CatalogParser.parse(
            File(root, "catalog/catalog.json").readBytes(), File(root, "catalog/catalog.json.sig").readBytes(), key,
        )
        return (parsed as app.granger.core.catalog.ParseResult.Ok).catalog
    }

    private fun wfViewModel(family: String? = null, installed: List<String> = emptyList()): app.granger.sources.SourceViewModel {
        val catalog = publishedCatalog()
        val library = app.granger.FakeLibrary()
        library.packList.value = installed.map { id -> catalog.packs.single { it.id == id }.let { app.granger.pack(it.zimUuid).copy(name = it.zimName) } }
        return app.granger.sources.SourceViewModel(
            app.granger.sources.CatalogScope.KIWIX, app.granger.FakeCatalogSource(catalog), app.granger.FakeDownloads(),
            library, app.granger.FakeBookshelf(),
            app.granger.FakeVolumes(listOf(app.granger.volume("/internal", "Phone", free = 41_000_000_000))), app.granger.FakePrefs(),
            app.granger.core.ai.DeviceTier.ASK, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            io = kotlinx.coroutines.Dispatchers.Main, family = family,
        )
    }

    /** The Wikipedia row alone: How much × Pictures and Add a subject, then WikiMed's row with its line. */
    private fun wfRows(name: String, installed: List<String> = emptyList(), slice: String? = null) {
        val vm = wfViewModel(installed = installed)
        if (slice != null) vm.chooseSlice("wikipedia_en", slice)
        shoot(name) {
            val ui by vm.ui.collectAsState()
            val rows = ui.sections.flatMap { it.rows }
            androidx.compose.material3.Surface {
                androidx.compose.foundation.layout.Column {
                    for (row in listOfNotNull(rows.firstOrNull { it.title == "Wikipedia" }, rows.firstOrNull { it.title == "WikiMed" })) {
                        app.granger.sources.CatalogItemRow(row, onGet = {}, onCancel = {}, onRetry = {}, onRemove = {})
                        androidx.compose.material3.HorizontalDivider()
                    }
                }
            }
        }
    }

    @Test fun wfWikipediaRow() = wfRows("wf-wikipedia-row")

    @Test fun wfWikipediaInside() = wfRows("wf-wikipedia-inside", installed = listOf("wikipedia_en_top1m_nopic"), slice = "wikipedia_en_top")

    @Test fun wfWikipediaHundred() = wfRows("wf-wikipedia-hundred", slice = "wikipedia_en_100")

    private fun wfScreen(name: String, family: String?) {
        val vm = wfViewModel(family = family)
        shoot(name) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.BROWSE,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.sources.SourceScreen(vm, title = "Kiwix", onBack = {}) }
        }
    }

    @Test fun wfKiwixList() = wfScreen("wf-kiwix-list", null)

    @Test fun wfSubjects() = wfScreen("wf-subjects", "subjects:wikipedia_en:wikipedia_en_top")

    @Test fun kiwixList() = kiwixListShot("kiwix-list", null)

    @Test fun kiwixFamily() = kiwixListShot("kiwix-family", "devdocs")

    @Test fun kiwixSearch() = kiwixListShot("kiwix-search", null, query = "wiki medicine")

    @Test fun kiwixBooksList() = kiwixBooksShot("kiwixbooks-list", ShelfView.LIST)

    @Test fun kiwixBooksGrid() = kiwixBooksShot("kiwixbooks-grid", ShelfView.GRID)

    @Test fun gutenbergList() = gutenbergShot("gutenberg-list", ShelfView.LIST)

    @Test fun gutenbergGrid() = gutenbergShot("gutenberg-grid", ShelfView.GRID)

    /** The Library with a Gutenberg collection beside the Classics (Plan GB Decision 4). */
    @Test fun libraryWithGutenberg() = shoot("library-gutenberg") {
        val classics = CollectionRow(ShelfCollection("c:gc", "Granger Classics", "literature", false, defaultKey = "gc"), emptyList(), books = List(12) { book("b$it") })
        val mine = CollectionRow(ShelfCollection("c:mine", "My books", "literature", false, defaultKey = "mine"), emptyList(), books = listOf(book("m")))
        val music = CollectionRow(
            ShelfCollection("c:gutenberg-m", "Music and books on music", "literature", false, defaultKey = "gutenberg-m"), emptyList(),
            books = List(412) { book("g$it") },
        )
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            LibraryContent(
                LibraryUi(libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(classics, music, mine)))), false),
                SnackbarHostState(), {}, {}, {}, downloadsBadge = 0, onDownloads = {}, onGetMore = {}, onImport = {},
            )
        }
    }

    @Test fun booksList() = booksShot("books-list", ShelfView.LIST)

    @Test fun booksGrid() = booksShot("books-grid", ShelfView.GRID)

    /** All music, forty albums of two songs, at 360 dp; [tab] is clicked before the shot. */
    private fun musicShot(name: String, tab: String, albumView: ShelfView = ShelfView.LIST) {
        val tracks = classics.take(40).flatMapIndexed { i, (title, author) ->
            (1..2).map { n ->
                app.granger.music.song("s$i-$n", title = if (n == 1) title else "$title, part two", album = title, albumArtists = author.ifEmpty { "Anonymous" },
                    trackNo = n, date = "${1900 + i}")
            }
        }
        val media = app.granger.music.FakeMediaLibrary(tracks)
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.ALBUMS, albumView) }
        val vm = app.granger.music.MusicViewModel(
            null, media, app.granger.FakeShelves(), app.granger.music.FakePlaylists(media), FakePlayerHub(),
            compute = kotlinx.coroutines.Dispatchers.Main, viewPrefs = prefs,
        )
        val nav = app.granger.music.MusicNav({}, {}, {}, {}, {}, {})
        var night by mutableStateOf(true)
        compose.setContent {
            GrangerTheme(night = night) {
                GrangerShell(
                    rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LISTEN,
                    onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                ) { app.granger.music.MusicScreen(vm, nav) }
            }
        }
        compose.onNode(androidx.compose.ui.test.hasText(tab) and androidx.compose.ui.test.hasClickAction()).performClick()
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun musicSongs() = musicShot("music-songs", "Songs")

    @Test fun musicAlbumsList() = musicShot("music-albums-list", "Albums")

    @Test fun musicAlbumsGrid() = musicShot("music-albums-grid", "Albums", ShelfView.GRID)

    @Test fun musicArtists() = musicShot("music-artists", "Artists")

    /** Forty audiobooks, three part-way, two finished, one missing, at 360 dp. */
    private fun audiobooksShot(name: String, view: ShelfView) {
        val media = app.granger.music.FakeMediaLibrary()
        val books = classics.take(40).mapIndexed { i, (title, author) ->
            audiobook("a$i", title, author, durationMs = 100_000L).let { if (i == 5) it.copy(status = app.granger.core.library.PackStatus.MISSING) else it }
        }
        media.bookList.value = books
        media.chapterList.value = books.map { chapter(it, 0, 100_000L) }
        val listening = app.granger.audiobooks.FakeListening()
        listening.stateList.value = listOf(2 to 40L, 9 to 30L, 17 to 20L).map { (i, at) ->
            app.granger.core.library.AudiobookState(books[i].id, "track:phone:a$i-f", 35_000L, 1f, true, 0, false, at)
        } + listOf(0, 3).map { i -> app.granger.core.library.AudiobookState(books[i].id, "track:phone:a$i-f", 100_000L, 1f, true, 0, true, 5L) }
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.AUDIOBOOKS, view) }
        val vm = app.granger.audiobooks.AudiobooksViewModel(
            null, media, app.granger.FakeShelves(), listening, FakePlayerHub(), compute = kotlinx.coroutines.Dispatchers.Main, viewPrefs = prefs,
        )
        shoot(name) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LISTEN,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.audiobooks.AudiobookShelfScreen(vm, onBook = {}, onBack = {}) }
        }
    }

    @Test fun audiobooksList() = audiobooksShot("audiobooks-list", ShelfView.LIST)

    @Test fun audiobooksGrid() = audiobooksShot("audiobooks-grid", ShelfView.GRID)

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

    // Plan DL Task 3: Downloads at 360 dp. An onboarding round running, a paused queue, and a retrying and a stopped file.
    private val dlNow = 1_000_000L
    private fun dl(
        id: String, title: String, state: app.granger.core.downloads.DownloadState, size: Long, done: Long = 0, order: Long = 0,
        group: String? = "onboarding:1", wifiOnly: Boolean = true, attempts: Int = 0, retryAt: Long? = null, error: String? = null,
        kind: app.granger.core.downloads.ItemKind = app.granger.core.downloads.ItemKind.PACK,
    ) = app.granger.core.downloads.DownloadRecord(
        itemId = id, hash = "a".repeat(64), kind = kind, title = title, urls = listOf("https://download.kiwix.org/zim/$id.zim"),
        sizeBytes = size, filePath = "/phone/packs/$id.zim", snapshotDate = "", groupId = group, wifiOnly = wifiOnly, state = state,
        bytesDone = done, error = error, createdAt = dlNow - 600_000, updatedAt = dlNow - 60_000, queueOrder = order, attempts = attempts,
        retryAt = retryAt,
    )

    private val dlRound = listOf(
        dl("voice", "Granger voice", app.granger.core.downloads.DownloadState.INSTALLED, 80_000_000, kind = app.granger.core.downloads.ItemKind.VOICE),
        dl("gc-hamlet", "Hamlet", app.granger.core.downloads.DownloadState.INSTALLED, 400_000, kind = app.granger.core.downloads.ItemKind.BOOK),
        dl("wikipedia_en_medicine_maxi", "WikiMed", app.granger.core.downloads.DownloadState.RUNNING, 1_860_000_000, done = 612_000_000, order = 2),
        dl("zimgit-knots_en", "Knots", app.granger.core.downloads.DownloadState.QUEUED, 31_000_000, order = 3),
        dl("ifixit_en_all", "iFixit repair guides", app.granger.core.downloads.DownloadState.QUEUED, 3_570_000_000, order = 4),
        dl("wikivoyage_en_all_maxi", "Wikivoyage travel guides", app.granger.core.downloads.DownloadState.QUEUED, 1_020_000_000, order = 5),
        dl("wikipedia_en_top_maxi", "Wikipedia: best articles", app.granger.core.downloads.DownloadState.QUEUED, 6_990_000_000, order = 6),
        dl("gemma", "Gemma 4 E2B", app.granger.core.downloads.DownloadState.QUEUED, 2_000_000_000, order = 7, kind = app.granger.core.downloads.ItemKind.MODEL),
    )

    @Composable
    private fun DownloadsShot(records: List<app.granger.core.downloads.DownloadRecord>, live: app.granger.core.downloads.DownloadLive) {
        val volumes = listOf(app.granger.downloads.VolumeSpace("/phone", "Phone", 41_000_000_000))
        val summary = app.granger.core.downloads.DownloadQueueSummary.of(records, live, dlNow, volumes.associate { it.root to it.freeBytes })
        val ui = app.granger.downloads.DownloadsModel.of(records, summary, dlNow - 3_600_000, volumes = volumes)
        app.granger.downloads.DownloadsContent(ui, onBack = {}, app.granger.downloads.DownloadsActions(), now = dlNow)
    }

    @Test fun downloadsRunning() = shoot("downloads-running") {
        DownloadsShot(
            dlRound,
            app.granger.core.downloads.DownloadLive(
                app.granger.core.downloads.QueueProgress("wikipedia_en_medicine_maxi", "a".repeat(64), 612_000_000, 2_100_000),
                batchStartedAt = dlNow - 600_000,
            ),
        )
    }

    @Test fun downloadsPaused() = shoot("downloads-paused") {
        DownloadsShot(
            dlRound.map { if (it.state == app.granger.core.downloads.DownloadState.RUNNING) it.copy(state = app.granger.core.downloads.DownloadState.QUEUED) else it },
            app.granger.core.downloads.DownloadLive(paused = true, batchStartedAt = dlNow - 600_000),
        )
    }

    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    @Test fun downloadsTrouble() = shoot("downloads-trouble") {
        val trouble = dlRound.map {
            when (it.itemId) {
                "zimgit-knots_en" -> it.copy(attempts = 2, retryAt = dlNow + 115_000, error = "download.kiwix.org closed the connection early")
                else -> it
            }
        } + dl("wikem_en_all_maxi", "WikEM emergency medicine", app.granger.core.downloads.DownloadState.FAILED, 375_000_000, order = 1, error = "Kiwix no longer offers this file") +
            dl("gc-emma", "Emma", app.granger.core.downloads.DownloadState.FAILED, 900_000, order = 1, error = "Checksum mismatch: the download didn't match its expected checksum.", kind = app.granger.core.downloads.ItemKind.BOOK)
        DownloadsShot(
            trouble,
            app.granger.core.downloads.DownloadLive(
                app.granger.core.downloads.QueueProgress("wikipedia_en_medicine_maxi", "a".repeat(64), 612_000_000, 2_100_000),
                batchStartedAt = dlNow - 600_000,
            ),
        )
    }
}
