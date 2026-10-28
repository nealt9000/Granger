package app.granger.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.granger.player.VoiceButton
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wiki bar with Listen at a small phone's 360 dp, measured with real fonts (native graphics): the search box keeps
 * a whole label, never "Search Wi…", and Stop doesn't make the bar wider (it takes the star's place).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ReaderTopBarFitTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(voice: VoiceButton) = compose.setContent {
        ReaderTopBar(
            pageTitle = "Photosynthesis", packTitle = "Wikipedia", showPageActions = true, bookmarked = false, showMenu = true, hasHome = true,
            onBack = {}, onSearchPack = {}, onHome = {}, onRandom = {}, onFind = {}, onToggleBookmark = {},
            voice = voice, onReadAloud = {},
        )
    }

    private fun SemanticsNodeInteraction.overflows(): Boolean {
        val out = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        val r = out.single()
        // Whole: one line, not ellipsized, and its natural width within the room it got.
        return r.lineCount > 1 || r.isLineEllipsized(0) || r.multiParagraph.intrinsics.maxIntrinsicWidth > r.layoutInput.constraints.maxWidth
    }

    private fun SemanticsNodeInteraction.right() = getBoundsInRoot().right

    private fun assertFits(listen: String, next: String) {
        val label = compose.onNodeWithText("Search", useUnmergedTree = true)
        assertThat(label.overflows()).isFalse()
        // TalkBack still hears the whole prompt.
        compose.onNodeWithContentDescription("Search Wikipedia", useUnmergedTree = true).assertExists()
        assertThat(compose.onNodeWithContentDescription(listen).getBoundsInRoot().left).isLessThan(compose.onNodeWithContentDescription(next).getBoundsInRoot().left)
        assertThat(compose.onNodeWithContentDescription("More").right()).isAtMost(360.dp)
    }

    @Test fun idleTheBarFitsWithListen() {
        bar(VoiceButton.IDLE)
        assertFits("Listen", "Add bookmark")
    }

    @Test fun readingStopTakesTheStarsPlace() {
        bar(VoiceButton.READING)
        assertFits("Pause", "Stop")
        compose.onNodeWithContentDescription("Add bookmark").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Add bookmark").assertExists()
    }

    // 411 dp, a Pixel 6: room for "Wikipedia" beside the glass, not for "Search Wikipedia".
    @Config(qualifiers = "w411dp-h800dp-xxhdpi")
    @Test fun aWiderPhoneKeepsThePacksName() {
        bar(VoiceButton.READING)
        val label = compose.onNodeWithText("Wikipedia", useUnmergedTree = true)
        assertThat(label.overflows()).isFalse()
    }
}
