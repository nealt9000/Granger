package app.granger.theme

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeed
import app.granger.home.Card
import app.granger.home.CardTarget
import app.granger.home.Drawn
import app.granger.home.FeedUi
import app.granger.home.HomeContent
import app.granger.home.HomeUi
import app.granger.onboarding.OnboardingActions
import app.granger.onboarding.OnboardingContent
import app.granger.onboarding.OnboardingUi
import app.granger.onboarding.Step
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
}
