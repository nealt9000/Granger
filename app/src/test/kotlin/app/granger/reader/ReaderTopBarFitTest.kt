package app.granger.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
 * The wiki's bars at a small phone's 360 dp, measured with real fonts (native graphics): Main page, Listen and ⋯ sit
 * beside the title (while the page is read, Listen leaves for the mini player and nothing else moves), a usual title
 * stays whole, and a long one ends in an ellipsis on one line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ReaderTopBarFitTest {
    @get:Rule val compose = createComposeRule()

    private fun bars(voice: VoiceButton, title: String = "Photosynthesis") = compose.setContent {
        val actions = wikiBarActions(
            packTitle = "Wikipedia", hasPack = true, hasPage = true, hasHome = true, bookmarked = false,
            onBack = {}, onListen = {}, voice = voice,
        )
        Column {
            ReaderTopBar(title, actions)
            ReaderBottomBar(actions)
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    /** Whole: one line, not ellipsized, and its natural width within the room it got. */
    private fun TextLayoutResult.whole() =
        lineCount == 1 && !isLineEllipsized(0) && multiParagraph.intrinsics.maxIntrinsicWidth <= layoutInput.constraints.maxWidth

    private fun title() = compose.onNodeWithTag(ReaderBarTags.TITLE, useUnmergedTree = true)

    private fun assertBarsFit(vararg topButtons: String) {
        val lefts = topButtons.map { compose.onNodeWithContentDescription(it).getBoundsInRoot().left }
        assertThat(lefts).isInStrictOrder()
        assertThat(compose.onNodeWithContentDescription("More").getBoundsInRoot().right).isAtMost(360.dp)
        for (n in listOf("Contents", "Search Wikipedia", "Text settings", "Add bookmark")) {
            assertThat(compose.onNodeWithContentDescription(n).getBoundsInRoot().right).isAtMost(360.dp)
        }
    }

    @Test fun idleTheBarsFitAndTheTitleIsWhole() {
        bars(VoiceButton.IDLE)
        assertBarsFit("Back", MAIN_PAGE, "Listen", "More")
        assertThat(title().layout().whole()).isTrue()
    }

    @Test fun readingMainPageStaysAndTheTitleKeepsItsRoom() {
        bars(VoiceButton.READING)
        assertBarsFit("Back", MAIN_PAGE, "More")
        for (n in listOf("Listen", "Pause", "Stop")) compose.onNodeWithContentDescription(n).assertDoesNotExist()
        assertThat(title().layout().whole()).isTrue()
        // Back, Main page and ⋯ leave the title well over a third of the bar.
        val bounds = title().getBoundsInRoot()
        assertThat(bounds.right - bounds.left).isAtLeast(140.dp)
    }

    @Test fun aLongTitleEndsInAnEllipsisOnOneLine() {
        bars(VoiceButton.READING, title = "List of people who have walked on the Moon and returned")
        val r = title().layout()
        assertThat(r.lineCount).isEqualTo(1)
        assertThat(r.isLineEllipsized(0)).isTrue()
        assertBarsFit("Back", MAIN_PAGE, "More")
    }
}
