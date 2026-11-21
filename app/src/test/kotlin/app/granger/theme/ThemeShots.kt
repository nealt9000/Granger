package app.granger.theme

import app.granger.invoke
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
open class ThemeShots {
    @get:Rule val compose = createComposeRule()

    /** A subfolder for a language's shots ([ThemeShotsSpanish]); empty for English. */
    protected open val language: String get() = ""
    private val out = System.getProperty("granger.themeShots").orEmpty().let { if (it.isEmpty() || language.isEmpty()) it else "$it/$language" }

    @Before fun onlyWhenAsked() = assumeTrue(out.isNotEmpty())

    /** A resource in the shot's language, for finding what to tap. */
    private fun text(id: Int, vararg args: Any): String =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    private fun shoot(name: String, pictures: Boolean = false, content: @Composable () -> Unit) {
        var night by mutableStateOf(true)
        compose.setContent { GrangerTheme(night = night) { content() } }
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            // Pictures read off the main thread: give them a moment, then let their frames draw.
            if (pictures) repeat(8) { Thread.sleep(250); compose.waitForIdle() }
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val ids = listOf("medicine", "food", "making", "science", "technology", "maps", "reference", "literature", "thought")
    private val report = ReadinessReport(
        domains = ids.mapIndexed { i, id -> DomainCoverage(id, id, if (i < 3) Coverage.BASIC else Coverage.NONE, 0, 100, 400, emptyList()) },
        signals = listOf(Signal.Unsearchable("p", app.granger.UiText.Raw("Owls can't be searched: the pack has no index."))),
        storage = listOf(StorageRow(Source.KIWIX, 11_100_000_000, 40)),
        needsCatalogRefresh = false,
    )
    private val owls = RailEntry.Tab(RailTabUi(1L, "Owls", app.granger.UiText.Raw("Birds of the world"), 'O', available = true, current = true), at = 5L, pinned = true)
    private val blue = RailEntry.Play("album:Blue", "Blue", at = 1L)
    private val dayFeed = FeedUi(
        HomeFeed(1, "family:wikipedia", FeedKind.ON_THIS_DAY, "", 0, 0), app.granger.UiText.Raw("Wikipedia"), app.granger.UiText.Raw("October 26"),
        Drawn(listOf(Card("1863 – The Football Association is founded in London.", "Events", CardTarget.Article("u1", "October_26", "Events")))),
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
        // A manifest pack's row (Plan KB2 Task 4.2): "24 books · From Kiwix · Federation of American Scientists".
        val mmKey = "kiwixbooks-irp.fas.org_en_military-medicine"
        val military = CollectionRow(
            ShelfCollection("c:$mmKey", "Military Medicine", "literature", false, defaultKey = mmKey), emptyList(), books = List(24) { book("mm$it") },
        )
        val mmManifest = app.granger.core.library.ManifestCollection("Military Medicine", "books", "Federation of American Scientists", emptyList())
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            LibraryContent(
                LibraryUi(
                    libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(archive, classics, military, wikipedia, mine)))), false,
                    manifests = mapOf(mmKey to mmManifest),
                ),
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
                        BrowseSection("medicine", app.granger.UiText.Raw("Medicine & first aid"), medicine),
                        BrowseSection("making", app.granger.UiText.Raw("Making, repair & engineering"), making),
                        BrowseSection("maps", app.granger.UiText.Raw("Maps, places & travel"), travel),
                        BrowseSection("reference", app.granger.UiText.Raw("General reference"), reference),
                        BrowseSection(OTHER_SECTION, app.granger.UiText.Raw("Other"), other),
                        BrowseSection(MAPS_SECTION, app.granger.UiText.Raw("Maps"), maps),
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
    @Config(qualifiers = "+w360dp-h720dp")
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
        val card = NowPlayingCard(NowPlayingCard.NOW_PLAYING, app.granger.UiText.Raw("Blue in Green"), app.granger.UiText.Raw("Miles Davis"), null, CardKind.MUSIC, live = true, playing = true)
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LISTEN,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            ListenContent(
                ListenUi(card, listOf(KindRow("music", app.granger.UiText.Raw("Music"), "music", listOf(phone)), KindRow("audiobooks", app.granger.UiText.Raw("Audiobooks"), "audiobooks", listOf(librivox)))),
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
    @Config(qualifiers = "+w720dp-h520dp")
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
    @Config(qualifiers = "+w720dp-h560dp")
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
        compose.onNode(androidx.compose.ui.test.hasContentDescription(text(R.string.library_jump_to, "A"))).performTouchInput { down(center) }
        compose.onNode(androidx.compose.ui.test.hasContentDescription(text(R.string.library_jump_to, "A"))).performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, height * 11.5f)) }
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

    /** Real titles from the Music pack; the first four opened (one of them a scan), the rest titles only until opened. */
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

    /**
     * Plan KB2 Task 2.3: a manifest collection (Military Medicine-like) at 360 dp in its own order: books with no section
     * first, a "Handbooks" section with a work in parts ([openWork] shows its parts), a folded "Orders · 60" section,
     * and language chips (the collection holds English and Spanish). With [algorithms], Algorithms instead: 643 course
     * files folded under "Course materials · 643" ahead of the book's chapters.
     */
    private fun manifestShot(name: String, view: ShelfView, openWork: Boolean, algorithms: Boolean = false) {
        val zim = if (algorithms) "jeffe.cs.illinois.edu_en_all" else "irp.fas.org_en_military-medicine"
        fun kb(key: String, title: String, group: String, ord: Int, author: String = "Department of the Army", workKey: String? = null, partNo: Int? = null, language: String = "en") =
            app.granger.book("book:kiwix:$zim/$key", title, author = author).copy(
                origin = app.granger.core.library.BookOrigin.KIWIX, sourceRef = "$zim/$key.pdf",
                format = app.granger.core.library.BookFormat.PDF, groupName = group, ord = ord, workKey = workKey,
                workTitle = workKey?.let { "Hospital Corpsman" }, partNo = partNo, language = language,
            )
        // Algorithms: 643 course files folded ahead of the book's chapters (the manifest's group order).
        val algorithmsBooks = (1..643).map { kb("c$it", "Fall 2024 · Homework $it", "Course materials", it, author = "") } +
            listOf("Introduction", "Recursion", "Backtracking", "Dynamic Programming", "Greedy Algorithms", "Basic Graph Algorithms")
                .mapIndexed { i, t -> kb("b$i", t, "The book", 1000 + i, author = "Jeff Erickson") }
        val books = if (algorithms) algorithmsBooks else listOf(
            kb("emwar", "Emergency War Surgery", "", 0, author = "Borden Institute"),
            kb("fa", "First Aid for Soldiers", "", 1),
            kb("hc1", "Hospital Corpsman (Part 1 of 3)", "Handbooks", 2, author = "US Navy", workKey = "hc", partNo = 1),
            kb("hc2", "Hospital Corpsman (Part 2 of 3)", "Handbooks", 3, author = "US Navy", workKey = "hc", partNo = 2),
            kb("hc3", "Hospital Corpsman (Part 3 of 3)", "Handbooks", 4, author = "US Navy", workKey = "hc", partNo = 3),
            kb("sf", "Special Forces Medical Handbook", "Handbooks", 5),
            kb("pa", "Primeros auxilios en combate", "Handbooks", 6, language = "es"),
        ) + (1..60).map { kb("o$it", "Medical order $it", "Orders", 10 + it) }
        val key = "kiwixbooks-$zim"
        val title = if (algorithms) "Algorithms" else "Military Medicine"
        val collection = if (algorithms) {
            app.granger.core.library.ManifestCollection(title, "documents", "Jeff Erickson", listOf("Course materials", "The book"))
        } else {
            app.granger.core.library.ManifestCollection(title, "books", "Federation of American Scientists", listOf("Handbooks", "Orders"))
        }
        val shelves = app.granger.FakeShelves()
        shelves.shelfList.value = listOf(
            app.granger.core.library.Shelf(
                MediaType("literature", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:$key", title, "literature", false, defaultKey = key), emptyList(), books)),
            ),
        )
        val library = app.granger.FakeLibrary().apply {
            packList.value = listOf(pack("u-mm", title).copy(name = zim, scraper = "zimit", bookManifest = "/s/mm.gbooks"))
        }
        val prefs = app.granger.library.shelf.LibraryViewPrefs().apply { setView(app.granger.library.shelf.ShelfKind.BOOKS, view) }
        val vm = app.granger.library.CollectionViewModel(
            "c:$key", shelves, library, app.granger.FakeReadingSessions(), app.granger.FakeBookshelf(), app.granger.FakeDownloads(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main), viewPrefs = prefs,
            compute = kotlinx.coroutines.Dispatchers.Main,
            manifestOf = { collection },
        )
        if (openWork) vm.toggleWork("hc")
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
            app.granger.sources.FlavourChoice("top_maxi", app.granger.UiText.Raw("With pictures"), 6_990_000_000, chosen = true),
            app.granger.sources.FlavourChoice("top_nopic", app.granger.UiText.Raw("No pictures"), 2_750_000_000, chosen = false),
            app.granger.sources.FlavourChoice("top_mini", app.granger.UiText.Raw("Mini (intro paragraphs only)"), 300_000_000, chosen = false),
        )
        val rows = listOf(
            app.granger.sources.ItemRow(
                "top_maxi", "Wikipedia: best articles", app.granger.UiText.Raw("English · Updated October 2026 · CC BY-SA"),
                app.granger.UiText.Raw("The 50,000 best Wikipedia articles"), 6_990_000_000, app.granger.sources.ItemStatus.NotInstalled, null,
                flavours = flavours, license = "CC BY-SA 4.0",
            ),
            app.granger.sources.ItemRow(
                "med_maxi", "WikiMed", app.granger.UiText.Raw("English · Updated October 2026 · CC BY-SA"), app.granger.UiText.Raw("The largest medical encyclopedia, from Wikipedia"),
                1_860_000_000, app.granger.sources.ItemStatus.NotInstalled, "u-med",
                flavours = listOf(
                    app.granger.sources.FlavourChoice("med_maxi", app.granger.UiText.Raw("With pictures"), 1_860_000_000, chosen = true),
                    app.granger.sources.FlavourChoice("med_nopic", app.granger.UiText.Raw("No pictures"), 1_050_000_000, chosen = false, installed = true),
                ),
                switches = true, license = "CC BY-SA 4.0",
            ),
            app.granger.sources.ItemRow(
                "survivor", "Survivor Library", app.granger.UiText.Raw("English · Updated September 2026 · Licence not stated"),
                app.granger.UiText.Raw("Books and manuals on how things were done before electricity"), 220_350_000_000,
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
            app.granger.sources.FlavourChoice("med_nopic", app.granger.UiText.Raw("No pictures"), 1_050_000_000, chosen = false, installed = true),
            app.granger.sources.FlavourChoice("med_maxi", app.granger.UiText.Raw("With pictures"), 1_860_000_000, chosen = true),
            onSwitch = {}, onDismiss = {},
        )
    }

    /** "Survivor Library is 220 GB" and Wikipedia's offer of its smaller flavour (Plan KB Decision 9). */
    @Test fun sizeCheck() = shootDialog("size-check") {
        app.granger.sources.SizeCheckDialog(
            app.granger.sources.SizeCheck(
                "wikipedia_en_all_maxi", "Wikipedia", 115_000_000_000, 41_000_000_000, "Phone",
                smaller = app.granger.sources.FlavourChoice("wikipedia_en_all_nopic", app.granger.UiText.Raw("No pictures"), 48_000_000_000, chosen = false),
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

    /** Plan WF Decision 5: the offer to tidy up once Top 1 million is in. */
    @Test fun wfTidy() = shoot("wf-tidy") {
        androidx.compose.material3.Surface {
            app.granger.sources.TidyOfferCard(
                app.granger.sources.TidyOffer(
                    "a>b", "wikipedia_en_top1m_nopic", "wikipedia_en_top_nopic", "u-1m", "u-top", 2_700_000_000,
                    app.granger.UiText.Res(app.granger.R.string.sources_tidy_text, "Top 1 million", "Best 50,000", "2.7 GB"),
                    "Best 50,000",
                ),
                onRemove = {}, onKeep = {},
            )
        }
    }

    /** Plan KB2 Task 4.1: Get more → Books by kind over the published catalog, Military Medicine on the phone. */
    @Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi")
    @Test fun booksByKind() {
        val catalog = publishedCatalog()
        val library = app.granger.FakeLibrary()
        val military = catalog.packs.single { it.zimName == "irp.fas.org_en_military-medicine" }
        val music = catalog.packs.single { it.zimName == "gutenberg_en_lcc-m" }
        library.packList.value = listOf(military, music).map { app.granger.pack(it.zimUuid).copy(name = it.zimName) }
        val vm = app.granger.sources.SourceViewModel(
            app.granger.sources.CatalogScope.BOOKS, app.granger.FakeCatalogSource(catalog), app.granger.FakeDownloads(),
            library, app.granger.FakeBookshelf(),
            app.granger.FakeVolumes(listOf(app.granger.volume("/internal", "Phone", free = 41_000_000_000))), app.granger.FakePrefs(),
            app.granger.core.ai.DeviceTier.ASK, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            io = kotlinx.coroutines.Dispatchers.Main,
        )
        shoot("books-by-kind") {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) {
                app.granger.sources.BooksScreen(vm, onBack = {}, onOpenScope = {}, onOpenPack = {}, onOpenBook = { _, _ -> }, onArchive = {}, onImport = {})
            }
        }
    }

    /** Plan KB2 Task 3.2: an African Storybook set of 25 books over five levels, as the catalog's `bookSets` lists it. */
    private fun asbShotSet(language: String = "en", name: String = "English"): app.granger.core.catalog.BookSet {
        val base = app.granger.asbSet(language, name)
        val titles = listOf(
            "A very tall man", "Cows at the market", "Goat and the hyena", "Sing, little bird", "Grandma's garden",
            "The lion who could not roar", "Tinga Tinga", "Counting stars", "My school bus", "Rain, rain",
        )
        val books = (0 until 25).map { i ->
            val level = 1 + i % 5
            base.books.first().copy(
                id = "asb-$language-${"%032x".format(i)}", title = titles[i % titles.size] + if (i >= titles.size) " ${i / titles.size + 1}" else "",
                level = level, category = "Level $level", sizeBytes = 900_000L + i * 120_000L,
            )
        }
        return base.copy(
            books = books,
            presets = listOf(app.granger.core.catalog.Preset(base.id, base.title, books.map { it.id }, language)) +
                (1..5).map { n -> app.granger.core.catalog.Preset("${base.id}-level-$n", "${base.title}: Level $n", books.filter { it.level == n }.map { it.id }, language) },
        )
    }

    private fun asbViewModel(scope: app.granger.sources.CatalogScope, catalog: app.granger.core.catalog.Catalog, downloads: app.granger.FakeDownloads = app.granger.FakeDownloads()) =
        app.granger.sources.SourceViewModel(
            scope, app.granger.FakeCatalogSource(catalog), downloads,
            app.granger.FakeLibrary(), app.granger.FakeBookshelf(),
            app.granger.FakeVolumes(listOf(app.granger.volume("/internal", "Phone", free = 41_000_000_000))), app.granger.FakePrefs(),
            app.granger.core.ai.DeviceTier.ASK, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            io = kotlinx.coroutines.Dispatchers.Main, bookSet = "african-storybook-en",
        )

    /** Plan KB2 Task 3.2: Books by kind with African Storybook's English set on the Children's shelf. */
    @Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi")
    @Test fun asbBooksByKind() {
        val catalog = publishedCatalog().let { it.copy(bookSets = listOf(asbShotSet(), asbShotSet("sw", "Kiswahili"))) }
        val vm = asbViewModel(app.granger.sources.CatalogScope.BOOKS, catalog)
        shoot("asb-books-by-kind") {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) {
                app.granger.sources.BooksScreen(vm, onBack = {}, onOpenScope = {}, onOpenPack = {}, onOpenBook = { _, _ -> }, onArchive = {}, onImport = {})
            }
        }
    }

    /** Plan KB2 Task 3.2: an African Storybook set's screen: its sets (all, each level), then its books by level. */
    @Config(sdk = [35], qualifiers = "w360dp-h1400dp-xxhdpi")
    @Test fun asbSetScreen() {
        val set = asbShotSet()
        val downloads = app.granger.FakeDownloads()
        downloads.list.value = set.books.take(2).map {
            app.granger.record(it.id, app.granger.core.downloads.DownloadState.INSTALLED, kind = app.granger.core.downloads.ItemKind.BOOK,
                filePath = "/internal/books/granger/${it.id}_2026-11-16.epub")
        }
        val vm = asbViewModel(app.granger.sources.CatalogScope.BOOK_SET, app.granger.catalogOf().copy(bookSets = listOf(set)), downloads)
        shoot("asb-set") {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.sources.SourceScreen(vm, title = "Books", onBack = {}) }
        }
    }

    @Test fun wfKiwixList() = wfScreen("wf-kiwix-list", null)

    @Test fun wfSubjects() = wfScreen("wf-subjects", "subjects:wikipedia_en:wikipedia_en_top")

    @Test fun kiwixList() = kiwixListShot("kiwix-list", null)

    @Test fun kiwixFamily() = kiwixListShot("kiwix-family", "devdocs")

    @Test fun kiwixSearch() = kiwixListShot("kiwix-search", null, query = "wiki medicine")

    @Test fun kiwixBooksList() = kiwixBooksShot("kiwixbooks-list", ShelfView.LIST)

    @Test fun kiwixBooksGrid() = kiwixBooksShot("kiwixbooks-grid", ShelfView.GRID)

    @Test fun manifestSections() = manifestShot("kb2-sections", ShelfView.LIST, openWork = false)

    @Test fun manifestParts() = manifestShot("kb2-parts", ShelfView.LIST, openWork = true)

    @Test fun manifestGrid() = manifestShot("kb2-sections-grid", ShelfView.GRID, openWork = true)

    @Test fun manifestFolded() = manifestShot("kb2-folded", ShelfView.LIST, openWork = false, algorithms = true)

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
        // A manifest pack (Plan KB2 Task 4.2): its noun and provider from the manifest.
        val mmKey = "kiwixbooks-zimgit-military-medicine_en"
        val military = CollectionRow(
            ShelfCollection("c:$mmKey", "Military Medicine", "literature", false, defaultKey = mmKey), emptyList(),
            books = List(20) { book("mm$it") },
        )
        val mmManifest = app.granger.core.library.ManifestCollection("Military Medicine", "books", "Federation of American Scientists", emptyList())
        GrangerShell(
            rail = RailUi(listOf(owls, blue)), expanded = false, railVisible = true, selected = RailTool.LIBRARY,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {}, onChat = {}, onPlay = {},
            actions = RailEntryActions(onPin = {}, onUnpin = {}, onRemove = {}),
        ) {
            LibraryContent(
                LibraryUi(
                    libraryKinds(listOf(Shelf(MediaType("literature", "Literature", true, 0), listOf(classics, military, music, mine)))), false,
                    manifests = mapOf(mmKey to mmManifest),
                ),
                SnackbarHostState(), {}, {}, {}, downloadsBadge = 0, onDownloads = {}, onGetMore = {}, onImport = {},
            )
        }
    }

    @Test fun booksList() = booksShot("books-list", ShelfView.LIST)

    @Test fun booksGrid() = booksShot("books-grid", ShelfView.GRID)

    /** All music, forty albums of two songs, at 360 dp; [tab] is clicked before the shot. */
    private fun musicShot(name: String, tab: Int, albumView: ShelfView = ShelfView.LIST) {
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
        compose.onNode(androidx.compose.ui.test.hasText(text(tab)) and androidx.compose.ui.test.hasClickAction()).performClick()
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun musicSongs() = musicShot("music-songs", R.string.music_tab_songs)

    @Test fun musicAlbumsList() = musicShot("music-albums-list", R.string.music_tab_albums)

    @Test fun musicAlbumsGrid() = musicShot("music-albums-grid", R.string.music_tab_albums, ShelfView.GRID)

    @Test fun musicArtists() = musicShot("music-artists", R.string.music_tab_artists)

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

    @Config(qualifiers = "+w360dp-h1400dp")
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

    /** Plan SQ Phase 5: Search's results page. The view model searches on the main looper, so it is idled past Best matches' pause. */
    private fun shootSearch(name: String, vm: app.granger.search.SearchViewModel) {
        var night by mutableStateOf(true)
        compose.setContent {
            GrangerTheme(night = night) {
                app.granger.search.SearchScreen(
                    vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {},
                    onPlaySong = {}, onBack = {}, onGetMore = {},
                )
            }
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_000))
        for (n in listOf(true, false)) {
            night = n
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out).mkdirs()
            File(out, "$name-${if (n) "night" else "field"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private class ShotSearch(
        val titles: List<app.granger.core.library.SearchHit> = emptyList(),
        val books: List<app.granger.core.library.BookHit> = emptyList(),
    ) : app.granger.core.library.LibrarySearch {
        override suspend fun titles(query: String) = app.granger.core.library.TitleResults(titles)
        override suspend fun fullText(query: String) = emptyList<app.granger.core.library.SearchHit>()
        override suspend fun books(query: String) = books
    }

    private fun bestMatch(kind: app.granger.core.research.SourceKind, title: String, pack: String, before: String, sentence: String) =
        app.granger.core.research.BestMatches.Match(
            app.granger.core.research.Passage(kind, pack, title, packTitle = pack, location = title, text = before + sentence),
            before.length until before.length + sentence.length,
        )

    private fun bestOf(words: List<String>, vararg m: app.granger.core.research.BestMatches.Match) =
        app.granger.search.BestMatchSearch { kotlinx.coroutines.flow.flowOf(app.granger.core.research.BestMatches.Result(m.toList(), words, byMeaning = true, final = true)) }

    private val burnMatches = arrayOf(
        bestMatch(app.granger.core.research.SourceKind.ARTICLE, "Burn", "WikiMed", "Burns are injuries. ", "Cool the burn with cool running water for twenty minutes, and do not use ice."),
        bestMatch(app.granger.core.research.SourceKind.ARTICLE, "First aid for burns", "Wikivoyage", "", "Remove rings and tight clothing near the burn before it swells."),
        bestMatch(app.granger.core.research.SourceKind.BOOK, "Chapter XII", "Little Women", "", "Jo burned her hand on the kettle, and Marmee bound it in a cool wet cloth."),
        bestMatch(app.granger.core.research.SourceKind.ARTICLE, "Sunburn", "WikiMed", "", "A sunburn is a burn from ultraviolet light."),
    )

    @Test fun searchBest() = shootSearch(
        "search-best",
        app.granger.search.SearchViewModel(
            ShotSearch(
                titles = listOf(
                    app.granger.core.library.SearchHit("wikimed", "WikiMed", "Burn", "Burn"),
                    app.granger.core.library.SearchHit("book:granger:burns", "Granger Classics", "", "Poems of Robert Burns", author = "Robert Burns"),
                ),
            ),
            app.granger.FakeReadingSessions(), "how to treat a burn", best = bestOf(listOf("treat", "burn"), *burnMatches),
        ),
    )

    @Config(qualifiers = "+w640dp-h360dp")
    @Test fun searchBestLandscape() = shootSearch(
        "search-best-landscape",
        app.granger.search.SearchViewModel(ShotSearch(), app.granger.FakeReadingSessions(), "how to treat a burn", best = bestOf(listOf("treat", "burn"), *burnMatches)),
    )

    @Test fun searchDefine() = shootSearch(
        "search-define",
        app.granger.search.SearchViewModel(
            ShotSearch(titles = listOf(app.granger.core.library.SearchHit("wikipedia", "Wikipedia", "Serendipity", "Serendipity"))),
            app.granger.FakeReadingSessions(), "serendipity",
            best = bestOf(listOf("serendipity"), bestMatch(app.granger.core.research.SourceKind.ARTICLE, "Serendipity", "Wikipedia", "", "Serendipity is an unplanned fortunate discovery.")),
            define = app.granger.search.WordEntry {
                app.granger.core.research.Passage(
                    app.granger.core.research.SourceKind.ARTICLE, "wikt", "serendipity", packTitle = "Wiktionary", location = "serendipity",
                    text = "Etymology: Coined by Horace Walpole in 1754.\nNoun: serendipity (usually uncountable, plural serendipities) 1. A combination of events which have come together by chance to make a surprisingly good or wonderful outcome. 2. An unsought, unintended, and unexpected, but fortunate, discovery.",
                )
            },
        ),
    )

    @Test fun searchEmpty() = shootSearch(
        "search-empty",
        app.granger.search.SearchViewModel(ShotSearch(), app.granger.FakeReadingSessions(), "gin joints in casablanca"),
    )

    /**
     * A picture for a shot: `<name>.jpg` from the folder GRANGER_SHOT_PICTURES names when it's there (real pack pictures,
     * for looking at), else a flat stand-in of [w]×[h] in [rgb]. The plate with its shape read from the file.
     */
    private fun shotPlate(title: String, w: Int, h: Int, rgb: Int, sub: String? = null, flag: Boolean = false): app.granger.explore.Plate {
        val name = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val real = System.getenv("GRANGER_SHOT_PICTURES")?.let { File(it, "$name.jpg") }?.takeIf { it.isFile && !flag }
        val file = real ?: File(File(System.getProperty("java.io.tmpdir"), "ex-plates").apply { mkdirs() }, "$name.png").also { f ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            if (flag) {
                // A tricolour with a disc: an emblem, to be shown whole.
                for ((i, c) in listOf(0xFF1B4D8F.toInt(), 0xFFFFFFFF.toInt(), 0xFFC8102E.toInt()).withIndex()) {
                    paint.color = c
                    canvas.drawRect(0f, h * i / 3f, w.toFloat(), h * (i + 1) / 3f, paint)
                }
                paint.color = 0xFFF2C230.toInt()
                canvas.drawCircle(w / 2f, h / 2f, h / 5f, paint)
            } else {
                canvas.drawColor(rgb or 0xFF000000.toInt())
                paint.color = 0x33FFFFFF
                canvas.drawCircle(w * 0.5f, h * 0.42f, minOf(w, h) * 0.28f, paint)
            }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        val ratio = if (bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else w.toFloat() / h
        return app.granger.explore.Plate(title, title, picture = file.path, ratio = ratio, whole = flag, sub = sub)
    }

    /**
     * Plan EX round 2, G0: the shared pieces of a spread on one sheet, at the width the rail leaves on a 390 dp phone: the
     * trail (folded), a numbered opening group with its head and captions, a finding head over a mosaic (led rows, three
     * across, an emblem shown whole, a plate with no picture), the ways on, rooms seen and the doors bar.
     */
    @Config(qualifiers = "+w334dp-h1560dp")
    @Test fun spreadPieces() {
        val opening = listOf(shotPlate("Airplane", 300, 200, 0x5B7C99), shotPlate("Airfig 3", 280, 200, 0x7A6A55), shotPlate("Airfig 7", 300, 180, 0x44606B))
        val wall = listOf(
            shotPlate("Car", 300, 200, 0x8C5A3C), shotPlate("Train", 300, 190, 0x4F5D4A), shotPlate("Bicycle", 280, 210, 0x6B6F7A),
            shotPlate("Ship", 300, 200, 0x35566B), shotPlate("Flag of the Netherlands", 255, 170, 0, flag = true), shotPlate("Bus", 290, 200, 0x9A4B3A),
            shotPlate("Boat", 300, 190, 0x4B7A7A), app.granger.explore.Plate("Hovercraft", "Hovercraft", blurb = "A hovercraft is an amphibious craft that travels over land, water, mud and ice."), shotPlate("Submarine", 300, 180, 0x2F4050),
            shotPlate("Charles Lindbergh", 220, 300, 0x6A5A4A, sub = "1902–1974"), shotPlate("Amelia Earhart", 220, 290, 0x7B6B5B, sub = "1897–1937"), shotPlate("Chuck Yeager", 220, 300, 0x4F5560, sub = "1923–2020"),
        )
        shoot("spread-pieces", pictures = true) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                app.granger.explore.Trail(listOf("Explore", "Technology", "Cars, planes & ships").map { app.granger.explore.TrailStep(it) {} }, here = "Airplane")
                app.granger.explore.PlateRowView(app.granger.explore.openingRow(opening)!!, onOpen = {}, firstNumber = 1)
                app.granger.explore.SpreadHead(
                    "Technology · Cars, planes & ships", "Airplane", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), large = true,
                    standfirst = "An airplane is a fixed-wing aircraft that is propelled forward by thrust from a jet engine, propeller, or rocket engine.",
                )
                app.granger.explore.Captions(
                    listOf("A United Airlines Boeing 777.", "The Wright Flyer’s first flight on 17 December 1903.", "The Concorde supersonic transport aircraft."),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                app.granger.explore.SpreadHead("The same things, other rooms", "One in five is also a weapon", Modifier.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 12.dp))
                app.granger.explore.PlateMosaic(app.granger.explore.plateRows(wall), onOpen = {})
                Box(Modifier.padding(top = 24.dp)) {
                    app.granger.explore.WaysOn(
                        "Three ways on",
                        listOf(
                            app.granger.explore.WayOn("Close in meaning", "Helicopter") {}, app.granger.explore.WayOn("From its History chapter", "Wright brothers") {},
                            app.granger.explore.WayOn("Anywhere in this room", "Wander") {},
                        ),
                    )
                }
                app.granger.explore.RoomsSeen("Rooms seen in Technology", 3, 8, Modifier.padding(top = 8.dp)) {}
                app.granger.explore.DoorsBar(
                    app.granger.explore.DoorKind.ROOM, app.granger.explore.Door("The internet") {}, app.granger.explore.Door("Spaceflight") {}, position = 3, total = 8,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    /**
     * Plan EX round 2, D3: a room (Cars, planes & ships) as the phone draws it beside the rail: the trail, the head and its
     * standfirst, the room through time, the views, the mosaic, where its topics also hang, the ways on, the doors bar.
     */
    @Config(qualifiers = "+w390dp-h2150dp")
    @Test fun room() {
        fun mark(title: String, y0: Long, y1: Long? = null) =
            app.granger.core.library.TimeMark(1, "top", title, title, app.granger.core.library.TimeKind.POINT, y0, 0, y1, null, 9, emptySet(), 1)
        fun article(title: String, w: Int = 300, h: Int = 200, rgb: Int = 0x5B6C7A, year: Long? = null, flag: Boolean = false): app.granger.explore.ExploreArticle {
            val p = shotPlate(title, w, h, rgb, flag = flag)
            return app.granger.explore.ExploreArticle("top", title.replace(' ', '_'), title, p.picture, year?.let { mark(title, it) }, p.ratio, p.whole)
        }
        fun facet(id: String, en: String, subject: String = "technology") = app.granger.core.library.Facet(subject, id, en, "")
        val wall = listOf("Car", "Train", "Bicycle", "Ship", "Titanic", "Bus", "Boat", "Submarine", "Airplane", "Helicopter", "Rocket", "Motorcycle")
            .mapIndexed { i, t -> article(t, rgb = listOf(0x8C5A3C, 0x4F5D4A, 0x6B6F7A, 0x35566B, 0x2F4050, 0x9A4B3A)[i % 6]) }
        val ui = app.granger.explore.SubjectUi(
            app.granger.explore.Subject.TECHNOLOGY, 882, wall, emptyList(), more = true,
            eras = listOf(app.granger.explore.Era.PREHISTORY to 1, app.granger.explore.Era.ANCIENT to 4, app.granger.explore.Era.EARLY_MODERN to 9, app.granger.explore.Era.C19 to 38, app.granger.explore.Era.WARS to 79, app.granger.explore.Era.RECENT to 97),
            dated = 228, facet = facet("vehicles", "Cars, planes & ships"),
            room = app.granger.explore.RoomUi(
                "Everything people have built to carry themselves, on land, on water, in the air and into space.",
                app.granger.explore.RoomFinding(article("Boat", year = -899_999), 228, 1900, 171),
                story = listOf(
                    article("Boat", year = -899_999), article("Trireme", year = -700), article("Manila galleon", year = 1565), article("Electric car", year = 1835),
                    article("Titanic", year = 1909), article("International Space Station", year = 1998),
                ),
                overlaps = listOf(
                    app.granger.explore.RoomOverlap(app.granger.explore.Subject.TECHNOLOGY, facet("weapons", "Weapons & warfare"), 179, listOf("Aircraft carrier", "F-16", "MiG-21")),
                    app.granger.explore.RoomOverlap(app.granger.explore.Subject.PLACES, facet("landmarks", "Buildings & landmarks", "places"), 54, listOf("Titanic", "HMS Hood", "Bismarck")),
                    app.granger.explore.RoomOverlap(app.granger.explore.Subject.HISTORY, facet("war", "Wars & battles", "history"), 49, listOf("Battleship", "Vasa", "HMS Dreadnought")),
                    app.granger.explore.RoomOverlap(app.granger.explore.Subject.HISTORY, facet("disasters", "Disasters & tragedies", "history"), 37, listOf("Titanic", "Britannic", "Carpathia")),
                ),
                before = facet("internet", "The internet"), after = facet("space", "Spaceflight"), position = 3, total = 8,
            ),
        )
        shoot("room", pictures = true) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.explore.RoomContent(ui, app.granger.explore.ExploreActions()) }
        }
    }

    /**
     * Plan EX round 2, D2: a subject (Technology) beside the rail: the trail, three opening plates and their captions, the
     * head, Wander and Quiz, the rooms in pairs each over its sentence, all its topics, in your books, the doors bar.
     */
    @Config(qualifiers = "+w390dp-h2320dp")
    @Test fun subject() {
        fun mark(title: String, y0: Long) = app.granger.core.library.TimeMark(1, "top", title, title, app.granger.core.library.TimeKind.POINT, y0, 0, null, null, 9, emptySet(), 1)
        fun article(title: String, rgb: Int = 0x5B6C7A, year: Long? = null): app.granger.explore.ExploreArticle {
            val p = shotPlate(title, 300, 200, rgb)
            return app.granger.explore.ExploreArticle("top", title.replace(' ', '_'), title, p.picture, year?.let { mark(title, it) }, p.ratio)
        }
        fun room(id: String, en: String, es: String, n: Int, picture: String, rgb: Int, description: String, finding: app.granger.explore.RoomFinding? = null) = app.granger.explore.FacetCard(
            app.granger.core.library.Facet("technology", id, en, es), n, emptyList(), shotPlate(picture, 300, 200, rgb).picture, description, finding, told = true,
        )
        val rooms = listOf(
            room("computers", "Computers & software", "Computadoras y software", 1_204, "Computer", 0x4F5D4A, "Machines that compute, and the programs that run on them.",
                app.granger.explore.RoomFinding(article("Gantt chart", year = 1910), 310, 1900, 262)),
            room("internet", "The internet", "Internet", 512, "Internet", 0x35566B, "The network of networks, and what people do on it.",
                app.granger.explore.RoomFinding(article("News agency", year = 1835), 96, 1900, 61)),
            room("vehicles", "Cars, planes & ships", "Autos, aviones y barcos", 882, "Ship", 0x2F4050, "Everything people have built to carry themselves.",
                app.granger.explore.RoomFinding(article("Boat", year = -899_999), 228, 1900, 171)),
            room("space", "Spaceflight", "Vuelos espaciales", 214, "Neil Armstrong", 0x6B6F7A, "Rockets, probes, stations and the people who flew them.",
                app.granger.explore.RoomFinding(article("Rocket", year = 1232), 66, 1900, 64)),
            room("machines", "Machines & gadgets", "Máquinas y aparatos", 655, "Telephone", 0x8C5A3C, "Tools and devices, from the wheel to the mobile phone.",
                app.granger.explore.RoomFinding(article("Clothing", year = -56_999), 120, 1800, 44)),
            room("inventions", "Inventions & inventors", "Inventos e inventores", 398, "Thomas Edison", 0x7A6A55, "The people who made new things, and the things they made.",
                app.granger.explore.RoomFinding(article("Johannes Gutenberg", year = 1400), 240, 1800, 118)),
            // Two dated topics: too few for its dates to say anything, so the room says what it is.
            room("energy", "Energy & power", "Energía", 263, "Energy", 0x9A4B3A, "Coal, petroleum, electricity and nuclear power: where the world's energy comes from."),
            room("weapons", "Weapons & warfare", "Armas y guerra", 742, "Nuclear weapon", 0x44606B, "From the sword and the arrow to the nuclear weapon.",
                app.granger.explore.RoomFinding(article("Arrow", year = -62_000), 6, null, 0)),
        )
        val ui = app.granger.explore.SubjectUi(
            app.granger.explore.Subject.TECHNOLOGY, 4_611, emptyList(), emptyList(), more = true, dated = 1_180, datedMain = 700, facets = rooms,
            hero = app.granger.explore.ArticleLook(null, "Technology is the application of conceptual knowledge to achieve practical goals, especially in a reproducible way."),
            opening = listOf(article("Technology", 0x7A6A55), article("Computer", 0x4F5D4A), article("Telephone", 0x8C5A3C)),
            finding = app.granger.explore.RoomFinding(null, 1_180, 1900, 640),
            books = listOf(app.granger.explore.ExploreBook("b1", "The Mechanical Arts", null, 1L), app.granger.explore.ExploreBook("b2", "On Aqueducts", null, 2L)),
            collections = listOf(app.granger.explore.ExploreCollection("c:granger-classics", "Granger Classics", 4)),
            doors = app.granger.explore.SubjectDoors(app.granger.explore.Subject.HEALTH, app.granger.explore.Subject.BUSINESS, 7, 17),
            seen = rooms.take(3).mapTo(HashSet()) { it.facet.key },
        )
        shoot("subject", pictures = true) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.explore.SubjectPageContent(ui, app.granger.explore.ExploreActions()) }
        }
    }

    /**
     * Plan EX round 2, D1: Explore's landing beside the rail on a 390 dp phone. Today's spread (Airplane in three numbered
     * plates, its captions and what its page holds), the seventeen subjects as a mosaic under their finding, Through time
     * as bars, and the three ways on.
     */
    @Config(qualifiers = "+w390dp-h2790dp")
    @Test fun landing() {
        val e = app.granger.explore.Subject.entries.associateBy { it.id }
        val doors = listOf(
            Triple("society", 6_017, "Society"), Triple("places", 5_748, "Geography"), Triple("science", 5_233, "Science"), Triple("sports", 4_040, "Sport"),
            Triple("film", 4_005, "Film"), Triple("technology", 3_538, "Technology"), Triple("history", 3_503, "History"), Triple("music", 3_179, "Music"),
            Triple("nature", 2_563, "Nature"), Triple("business", 2_540, "Economy"), Triple("religion", 2_384, "Religion"), Triple("health", 1_919, "Medicine"),
            Triple("literature", 1_516, "Literature"), Triple("games", 1_065, "Game"), Triple("languages", 766, "Language"), Triple("food", 662, "Food"), Triple("art", 478, "Art"),
        ).mapIndexed { i, (id, n, picture) ->
            app.granger.explore.SubjectDoor(e.getValue(id), n, emptyList(), emptyList(), picture = shotPlate(picture, 300, 200, 0x40545E + i * 0x070503).picture)
        }
        val eras = listOf(687, 1_219, 1_954, 2_890, 4_713, 5_623, 28_141).mapIndexed { i, n -> app.granger.explore.EraRow(app.granger.explore.Era.entries[i], n, emptyList()) }
        val airplane = shotPlate("Airplane", 300, 200, 0x5B7C99)
        val spanish = language == "es"
        val ui = app.granger.explore.ExploreUi(
            articles = 146_618, doors = doors, eras = eras, sources = listOf("a", "b", "c", "d", "e", "f"), rooms = 108,
            roomKeys = (1..108).mapTo(HashSet()) { "r$it" }, seen = (1..14).mapTo(HashSet()) { "r$it" },
            featured = app.granger.explore.FeaturedTopic(
                app.granger.explore.Subject.TECHNOLOGY, 3_538,
                app.granger.explore.ExploreArticle("top", if (spanish) "Avión" else "Airplane", if (spanish) "Avión" else "Airplane", airplane.picture, ratio = airplane.ratio), emptyList(),
                if (spanish) "Un avión es una aeronave de ala fija, más pesada que el aire, capaz de volar impulsada por uno o más motores."
                else "An airplane is a fixed-wing aircraft that is propelled forward by thrust from a jet engine, propeller, or rocket engine.",
                room = app.granger.core.library.Facet("technology", "transport", "Cars, planes & ships", "Autos, aviones y barcos"),
                figures = listOf(
                    app.granger.explore.SpreadFigure(shotPlate("Airfig 3", 280, 200, 0x7A6A55).picture!!, if (spanish) "El primer vuelo del Wright Flyer, el 17 de diciembre de 1903" else "The Wright Flyer's first flight on 17 December 1903"),
                    app.granger.explore.SpreadFigure(shotPlate("Airfig 7", 300, 180, 0x44606B).picture!!, if (spanish) "El avión supersónico de pasajeros Concorde" else "The Concorde supersonic transport aircraft"),
                ),
                kinds = 72, people = 12, chapters = 6,
            ),
        )
        shoot("landing", pictures = true) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) { app.granger.explore.ExploreContent(ui, app.granger.explore.ExploreActions(), day = java.time.LocalDate.of(2026, 11, 18).toEpochDay()) }
        }
    }

    /**
     * Plan EX round 2, D5: a broad topic as a spread, beside the rail on a 390 dp phone: Airplane's opening plates, its
     * diagram, its kinds by family, its story by year, the people near it, Ship and Car beside it, its other chapters,
     * the ways on and the doors to its neighbours in the room.
     */
    @Config(qualifiers = "+w390dp-h3780dp")
    @Test fun broadTopic() {
        fun pic(title: String, rgb: Int = 0x5B6C7A, w: Int = 300, h: Int = 200) = shotPlate(title, w, h, rgb).picture
        fun a(title: String, rgb: Int = 0x5B6C7A, w: Int = 300, h: Int = 200) = app.granger.topic.TopicArticle("top", title.replace(' ', '_'), title, "", pic(title, rgb, w, h))
        fun plain(title: String) = app.granger.topic.TopicArticle("top", title.replace(' ', '_'), title, "", null)
        fun life(title: String, y0: Long, y1: Long) = app.granger.core.library.TimeMark(1, "top", title, title, app.granger.core.library.TimeKind.LIFE, y0, 0, y1, null, 9, emptySet(), 1)
        fun family(name: String, vararg kinds: String, more: Int) =
            app.granger.topic.BroadFamily(name, kinds.map { a(it, 0x4A6478) }, kinds.map { plain(it) } + (1..more).map { plain("$name $it") })
        fun step(year: Long, text: String, fig: Int?) = app.granger.topic.StoryEntry(year, text, fig?.let { pic("Airfig $it", 0x7A6A55, 280, 200) }, null)
        val broad = app.granger.topic.BroadTopic(
            under = 72,
            groups = listOf(app.granger.topic.BroadGroup("Jet aircraft", 24), app.granger.topic.BroadGroup("Transport airplanes", 19), app.granger.topic.BroadGroup("Combat airplanes", 17)),
            families = listOf(
                family("Jet aircraft", "Boeing 747", "Boeing 787 Dreamliner", "Boeing B-52 Stratofortress", more = 21),
                family("Transport airplanes", "Airbus A380", "Concorde", "Boeing 777", more = 16),
                family("Combat airplanes", "General Dynamics F-16 Fighting Falcon", "Lockheed Martin F-22 Raptor", "Mikoyan MiG-29", more = 14),
            ),
            story = app.granger.topic.BroadStory("History", listOf(
                step(1868, "Le Bris and his glider, Albatros II, photographed by Nadar, 1868", 0), step(1895, "Otto Lilienthal in mid-flight, Berlin, c. 1895", 1),
                step(1903, "The Wright Flyer's first flight on 17 December 1903", 3), step(1906, "Santos-Dumont 14-bis, between 1906 and 1907", 4),
                app.granger.topic.StoryEntry(1939, "World War II", null, plain("World War II")),
                step(2024, "An American Airlines Boeing 737-800 landing at Ronald Reagan Washington National Airport in July 2024", 6),
            )),
            people = listOf(
                "Igor Sikorsky" to (1889L to 1972L), "Charles Lindbergh" to (1902L to 1974L), "Chuck Yeager" to (1923L to 2020L),
                "Manfred von Richthofen" to (1892L to 1918L), "Neil Armstrong" to (1930L to 2012L), "John Glenn" to (1921L to 2016L),
            ).map { (n, y) -> app.granger.topic.BroadPerson(a(n, 0x6B5D52, 200, 260), life(n, y.first, y.second)) } + (1..6).map { app.granger.topic.BroadPerson(plain("Pilot $it"), null) },
            chapters = listOf(
                app.granger.topic.BroadChapter("Propulsion", listOf("Engine", "Propeller", "Electric motor", "Speed of sound", "Mach number", "Fuel cell").map { plain(it) }, null, null),
                app.granger.topic.BroadChapter("Design and manufacture", listOf("Boeing", "Airbus", "Federal Aviation Administration").map { plain(it) }, null, null),
                app.granger.topic.BroadChapter("Environmental impact", listOf("Combustion", "Soot", "Greenhouse gas", "Carbon dioxide", "Noise pollution").map { plain(it) }, null, null),
            ),
            figures = listOf(
                app.granger.topic.BroadFigure(pic("Airfig 3", 0x7A6A55, 280, 200)!!, "The Wright Flyer's first flight on 17 December 1903", "History"),
                app.granger.topic.BroadFigure(pic("Airfig 7", 0x44606B, 300, 180)!!, "The Concorde supersonic transport aircraft", "History"),
            ),
            diagram = app.granger.topic.BroadFigure(pic("Airfig 14", 0xE8E4DA, 300, 190)!!, "Major components of an airplane", "Characteristics"),
            inlinks = 86,
            place = app.granger.topic.BroadPlace(
                "technology", app.granger.core.library.Facet("technology", "transport", "Cars, planes & ships", "Autos, aviones y barcos"),
                before = plain("Bicycle"), after = plain("Ship"), position = 5, total = 882,
            ),
            beside = listOf(
                app.granger.topic.BroadColumn(plain("Airplane"), 72, listOf("Jet aircraft", "Transport airplanes", "Combat airplanes"), listOf("Igor Sikorsky", "Charles Lindbergh", "Chuck Yeager"), 7, 86),
                app.granger.topic.BroadColumn(a("Ship", 0x3F6B78), 217, listOf("Cruisers", "Battleships", "Aircraft carriers"), listOf("Matthew Perry", "Robert Surcouf", "Vasco da Gama"), 8, 74),
                app.granger.topic.BroadColumn(a("Car", 0x6B6B4A), 201, listOf("Sports cars", "Compact cars", "Family cars"), listOf("Henry Ford", "Gottlieb Daimler", "Enzo Ferrari"), 11, 366),
            ),
        )
        val topic = app.granger.topic.Topic(
            "top", "Airplane",
            header = app.granger.topic.TopicHeader("Airplane", "An airplane is a fixed-wing aircraft that is propelled forward by thrust from a jet engine, propeller, or rocket engine. Airplanes come in a variety of sizes.", pic("Airplane", 0x5B7C99)),
            related = listOf(plain("Helicopter"), plain("Glider"), plain("Airship")), relatedBooks = emptyList(), broad = broad, done = true,
        )
        shoot("broadTopic", pictures = true) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) {
                app.granger.topic.TopicPage(topic, "Wikipedia", {}, {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}, null, onWander = {}, onExplore = {}, onOpenPlace = { _, _ -> })
            }
        }
    }

    /**
     * Plan EX round 2, D4: connections as a quilt, beside the rail on a 390 dp phone: Airplane in the middle, its groups
     * touching it under their tabs, the corner numbers, the key and the index of every group.
     */
    @Config(qualifiers = "+w390dp-h1000dp")
    @Test fun quilt() {
        fun pic(title: String, rgb: Int = 0x5B6C7A, w: Int = 300, h: Int = 200) = shotPlate(title, w, h, rgb).picture
        fun a(title: String, rgb: Int = 0x5B6C7A, w: Int = 300, h: Int = 200) = app.granger.topic.TopicArticle("top", title.replace(' ', '_'), title, "", pic(title, rgb, w, h))
        fun plain(title: String) = app.granger.topic.TopicArticle("top", title.replace(' ', '_'), title, "", null)
        fun many(name: String, n: Int) = (1..n).map { plain("$name $it") }
        val broad = app.granger.topic.BroadTopic(
            under = 60, groups = listOf(app.granger.topic.BroadGroup("Jet aircraft", 24)),
            families = listOf(app.granger.topic.BroadFamily(null, listOf(a("Boeing 747", 0x4A6478), a("Concorde", 0x44606B), a("General Dynamics F-16 Fighting Falcon", 0x6A6A55)),
                listOf(plain("Boeing 747"), plain("Concorde"), plain("General Dynamics F-16 Fighting Falcon")) + many("Kind", 57))),
            story = app.granger.topic.BroadStory("History", (listOf(a("Wright brothers", 0x7A6A55), a("World War I", 0x5A5A4A), a("Battle of Britain", 0x4A5A6A)) + many("Step", 21)).mapIndexed { i, t ->
                app.granger.topic.StoryEntry(1900L + i, t.title, t.picture, t)
            }),
            people = (listOf(a("Charles Lindbergh", 0x6B5D52, 200, 260), a("Chuck Yeager", 0x5D6B52, 200, 260)) + many("Pilot", 10)).map { app.granger.topic.BroadPerson(it, null) },
            chapters = listOf(
                app.granger.topic.BroadChapter("Propulsion", listOf(a("Engine", 0x555555), a("Propeller", 0x665544)) + many("Part", 11), null, null),
                app.granger.topic.BroadChapter("Environmental impact", many("Effect", 8), null, null),
                app.granger.topic.BroadChapter("Design and manufacture", many("Maker", 4), null, null),
                app.granger.topic.BroadChapter("Characteristics", many("Trait", 4), null, null),
            ),
        )
        val topic = app.granger.topic.Topic(
            "top", "Airplane", header = app.granger.topic.TopicHeader("Airplane", "An airplane is a fixed-wing aircraft.", pic("Airplane", 0x5B7C99)),
            related = listOf(a("Helicopter", 0x556677), a("Rocket", 0x443322)) + many("Kin", 10), relatedBooks = emptyList(), broad = broad, done = true,
        )
        val more = mapOf("Boeing_747" to 6, "Concorde" to 3, "Charles_Lindbergh" to 3, "Chuck_Yeager" to 4, "Wright_brothers" to 6, "World_War_I" to 102, "Battle_of_Britain" to 3,
            "Engine" to 7, "Propeller" to 7, "Helicopter" to 15, "Rocket" to 7).mapKeys { "top/" + it.key }
        shoot("quilt", pictures = true) {
            GrangerShell(
                rail = RailUi(listOf(owls)), expanded = false, railVisible = true, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            ) {
                app.granger.topic.TopicQuilt(
                    topic, topicOf = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
                    around = { _, _, plates -> app.granger.topic.QuiltAround(more = if (plates.isEmpty()) emptyMap() else more) }, cardOf = { _, _ -> null },
                    actions = app.granger.topic.QuiltActions({ _, _, _ -> }, { _, _ -> }, { _, _ -> }, null, {}),
                )
            }
        }
    }

    /** A thin room's mosaic (Words & grammar): words side by side, portraits upright, then words three across; and a first door. */
    @Config(qualifiers = "+w334dp-h760dp")
    @Test fun spreadThin() {
        fun word(title: String, blurb: String) = app.granger.explore.Plate(title, title, blurb = blurb)
        val wall = listOf(
            word("Noun", "In grammar, a noun is a word that represents a concrete or abstract thing."), word("Adjective", "An adjective is a word that describes or defines a noun or noun phrase."),
            shotPlate("Pāṇini", 200, 260, 0x7A5B3A, sub = "c. 520 BC"), shotPlate("William Tyndale", 200, 250, 0x4A3F35, sub = "1494–1536"), shotPlate("Jacob Grimm", 200, 260, 0x5A5F55, sub = "1785–1863"),
            word("Pronoun", "A pronoun is a word that substitutes for a noun or noun phrase."), word("Interjection", "An interjection is a word that occurs as an utterance on its own."), word("Adposition", "Adpositions are words used to express spatial or temporal relations."),
        )
        shoot("spread-thin", pictures = true) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                app.granger.explore.Trail(listOf("Explore", "Languages").map { app.granger.explore.TrailStep(it) {} }, here = "Words & grammar")
                app.granger.explore.SpreadHead(
                    "A small room in Languages", "Words & grammar", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp), large = true,
                    standfirst = "The parts of speech, and the people who studied them.",
                )
                app.granger.explore.PlateMosaic(app.granger.explore.plateRows(wall), onOpen = {})
                app.granger.explore.RoomsSeen("Rooms you have seen", 14, 108, Modifier.padding(top = 8.dp))
                app.granger.explore.DoorsBar(
                    app.granger.explore.DoorKind.ROOM, null, app.granger.explore.Door("Scripts & alphabets") {}, position = 1, total = 3, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * The same screens in Spanish (Plan L2 Task 4), under app/build/theme-shots/es, for clipped or wrapped labels:
 * ./gradlew :app:testDebugUnitTest --tests '*ThemeShotsSpanish*' -PthemeShots
 */
@Config(qualifiers = "+es")
class ThemeShotsSpanish : ThemeShots() {
    override val language: String get() = "es"
}
