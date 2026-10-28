package app.granger.player

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.granger.theme.NightColors
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The players sit on the dark background, so their icons and text must be drawn in the theme's on-background colour,
 * not the black [LocalContentColor][androidx.compose.material3.LocalContentColor] defaults to outside a Surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp") // Tall enough that nothing needs scrolling to.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerContentColorTest {
    @get:Rule val compose = createComposeRule()

    /** The brightest pixel's luminance (0..1) in the node's bounds. */
    private fun SemanticsNodeInteraction.brightest(): Float {
        val pixels = captureToImage().toPixelMap()
        var best = 0f
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            best = maxOf(best, 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue)
        }
        return best
    }

    private val onSurface = NightColors.onSurface.let { 0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue }

    private fun assertBright(node: SemanticsNodeInteraction, what: String, atLeast: Float = onSurface * 0.8f) {
        assertWithMessage("$what brightness").that(node.brightest()).isAtLeast(atLeast)
    }

    @Test fun musicPlayerIconsAndTextUseTheThemeContentColour() {
        val vm = PlayerViewModel(FakePlayerHub(musicUi()))
        compose.setContent { GrangerTheme { ListenPlayerScreen(vm, onClose = {}) } }

        for (d in listOf("Previous track", "Next track", "Shuffle off", "Repeat off", "Close player")) {
            assertBright(compose.onNodeWithContentDescription(d), d)
        }
        assertBright(compose.onNodeWithText("Song A"), "Title")
    }

    @Test fun audiobookPlayerIconsUseTheThemeContentColourAndDisabledStaysVisible() {
        val book = audiobook("b", title = "The Book")
        val chapters = listOf(chapter(book, 0, 60_000L), chapter(book, 1, 60_000L))
        val vm = PlayerViewModel(FakePlayerHub(bookUi(book, chapters[0], chapterCount = 2)), chapters = { MutableStateFlow(chapters) })
        compose.setContent { GrangerTheme { ListenPlayerScreen(vm, onClose = {}) } }

        for (d in listOf("Back 30 seconds", "Forward 30 seconds", "Next chapter", "Chapters", "Add bookmark", "Bookmarks", "Sleep timer")) {
            assertBright(compose.onNodeWithContentDescription(d), d)
        }
        assertBright(compose.onNodeWithText("The Book"), "Title")
        assertBright(compose.onNodeWithText("Skip silence"), "Skip silence label")

        // Chapter 1 of 2: previous is disabled, dimmed (38% alpha) but still well clear of the background.
        val previous = compose.onNodeWithContentDescription("Previous chapter")
        previous.assertIsNotEnabled()
        assertBright(previous, "Disabled previous chapter", atLeast = onSurface * 0.3f)
        compose.onNodeWithContentDescription("Next chapter").assertIsEnabled()
    }

    @Test fun miniPlayerIconsAreVisible() {
        compose.setContent { GrangerTheme { MiniPlayer(musicUi(), onPlayPause = {}, onSecondary = {}, onOpen = {}) } }
        assertBright(compose.onNodeWithContentDescription("Next track"), "Mini next track", atLeast = onSurface * 0.5f)
        assertBright(compose.onNodeWithText("Song A"), "Mini title", atLeast = onSurface * 0.5f)
    }
}
