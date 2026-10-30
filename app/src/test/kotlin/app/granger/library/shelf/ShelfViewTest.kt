package app.granger.library.shelf

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.granger.core.library.ShelfKey
import app.granger.settings.RailSide
import app.granger.shell.LocalRailSide
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class ShelfViewTest {
    @get:Rule val compose = createComposeRule()

    /** Two or three titles for each letter, A to Z, then a couple of digits. */
    private val titles = ('A'..'Z').flatMap { c -> listOf("${c}ook one", "The ${c}ook two") } + listOf("1984", "2001")

    private fun sections(list: List<String> = titles, byName: Boolean = true) = ShelfSections.of(
        list.sortedByShelfKey({ ShelfKey.of(it) }), byName, key = { it }, letter = { ShelfKey.letter(ShelfKey.of(it)) },
    )

    private fun header(letter: String) =
        compose.onNode(hasText(letter) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    private fun list(sections: ShelfSections<String>, rail: RailSide = RailSide.LEFT) = compose.setContent {
        CompositionLocalProvider(LocalRailSide provides rail) {
            ShelfList(sections, Modifier.fillMaxSize(), header = { item { Text("Find a title") } }) { Text(it, Modifier.fillMaxWidth().height(56.dp)) }
        }
    }

    @Test fun tappingALetterBringsItsHeaderIntoViewInTheList() {
        list(sections())
        header("M").assertDoesNotExist()
        compose.onNodeWithContentDescription("Jump to M").performClick()
        header("M").assertIsDisplayed()
        compose.onNodeWithText("Mook one").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to #").performClick()
        header("#").assertIsDisplayed()
        compose.onNodeWithText("1984").assertIsDisplayed()
    }

    @Test fun draggingDownTheStripLandsOnTheLetterUnderTheThumb() {
        list(sections())
        val a = compose.onNodeWithContentDescription("Jump to A").getBoundsInRoot()
        val m = compose.onNodeWithContentDescription("Jump to M").getBoundsInRoot()
        compose.onNodeWithContentDescription("Jump to A").performTouchInput {
            down(center)
            moveBy(Offset(0f, ((m.top + m.bottom) / 2 - (a.top + a.bottom) / 2).toPx()))
        }
        header("M").assertIsDisplayed()
        // The bubble shows the letter beside the thumb (the header is the other) until it lifts.
        compose.onAllNodesWithText("M").assertCountEquals(2)
        compose.onNodeWithContentDescription("Jump to A").performTouchInput { up() }
        compose.onAllNodesWithText("M").assertCountEquals(1)
    }

    @Test fun tappingALetterBringsItsHeaderIntoViewInTheGrid() {
        compose.setContent {
            ShelfGrid(sections(), cellMin = 104.dp, modifier = Modifier.fillMaxSize(), header = { item { Text("Find a title") } }) {
                Text(it, Modifier.height(160.dp))
            }
        }
        header("M").assertDoesNotExist()
        compose.onNodeWithContentDescription("Jump to M").performClick()
        header("M").assertIsDisplayed()
        compose.onNodeWithText("Mook one").assertIsDisplayed()
    }

    @Test fun everyLetterPresentIsAButtonForTalkBack() {
        list(sections(titles.filterNot { "Qook" in it }))
        for (c in ('A'..'Z') - 'Q') compose.onNodeWithContentDescription("Jump to $c").assertExists()
        compose.onNodeWithContentDescription("Jump to #").assertExists()
        compose.onAllNodesWithContentDescription("Jump to Q").assertCountEquals(0)
    }

    @Test fun noStripForThirtyItemsOrFewer() {
        list(sections(titles.take(30)))
        compose.onAllNodesWithContentDescription("Jump to A").assertCountEquals(0)
        compose.onNodeWithText("Aook one").assertIsDisplayed()
        header("A").assertDoesNotExist()
    }

    @Test fun noStripForASortWithoutLetters() {
        list(sections(byName = false))
        compose.onAllNodesWithContentDescription("Jump to A").assertCountEquals(0)
        header("A").assertDoesNotExist()
    }

    @Test fun theStripKeepsAwayFromTheRail() {
        list(sections(), rail = RailSide.LEFT)
        val width = compose.onRoot().getBoundsInRoot().right
        assertThat(compose.onNodeWithContentDescription("Jump to A").getBoundsInRoot().left).isGreaterThan(width / 2)
    }

    @Test fun withTheRailOnTheRightTheStripIsOnTheLeft() {
        list(sections(), rail = RailSide.RIGHT)
        val width = compose.onRoot().getBoundsInRoot().right
        assertThat(compose.onNodeWithContentDescription("Jump to A").getBoundsInRoot().right).isLessThan(width / 2)
    }
}
