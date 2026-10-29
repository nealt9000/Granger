package app.granger.browse

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.granger.core.library.PackStatus
import app.granger.pack
import app.granger.sources.AskReach
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class BrowseScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private val actions = BrowseActions(
        onOpenPack = { calls += "open:$it" },
        onGetPacks = { calls += "get:$it" },
        onDownloads = { calls += "downloads" },
        onReadiness = { calls += "readiness" },
        onRemove = { calls += "remove:${it.id}" },
    )

    private val wikipedia = PackItem(pack("w", "Wikipedia").copy(sizeBytes = 1_200_000_000), AskReach.MEANING)
    private val gone = PackItem(pack("g", "Gone", status = PackStatus.MISSING), AskReach.KEYWORDS)
    private val iceland = PackItem(pack("i", "Iceland").copy(sizeBytes = 80_000_000))

    private fun show(ui: BrowseUi) = compose.setContent { BrowseContent(ui, "/packs", downloadsBadge = 0, actions) }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left

    @Test fun eachPackIsARowWithItsSizeAndAskLineAndOpens() {
        show(BrowseUi(listOf(BrowseSection("reference", "General reference", listOf(gone, wikipedia)), BrowseSection(MAPS_SECTION, "Maps", listOf(iceland)))))
        compose.onNodeWithText("1.2 GB · Ask: by meaning and keywords").assertExists()
        // Maps say nothing about Ask.
        compose.onNodeWithText("80 MB").assertExists()
        compose.onNodeWithText("Wikipedia").performClick()
        compose.onNodeWithText("Iceland").performClick()
        // A missing pack says so instead of opening.
        compose.onNodeWithText("Gone").performClick()
        compose.onNodeWithText("File not found", substring = true).assertExists()
        assertThat(calls).containsExactly("open:w", "open:i").inOrder()
        // A section collapses.
        compose.onNodeWithText("Maps").performClick()
        compose.onNodeWithText("Iceland").assertDoesNotExist()
    }

    @Test fun aLongPressRemovesThePackAfterAsking() {
        show(BrowseUi(listOf(BrowseSection("reference", "General reference", listOf(wikipedia)))))
        compose.onNodeWithText("Wikipedia").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from phone").performClick()
        compose.onNodeWithText("Remove Wikipedia?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        assertThat(calls).containsExactly("remove:w")
    }

    @Test fun theHeaderIsGetMoreThenMoreWithDownloadsAndReadiness() {
        show(BrowseUi(listOf(BrowseSection("reference", "General reference", listOf(wikipedia)))))
        assertThat(compose.onNodeWithContentDescription("Get more").left()).isLessThan(compose.onNodeWithContentDescription("More").left())
        compose.onNodeWithContentDescription("Get more").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Readiness").performClick()
        assertThat(calls).containsExactly("get:null", "downloads", "readiness").inOrder()
    }

    @Test fun anEmptyBrowseSuggestsWhatToGetFirst() {
        show(BrowseUi(emptyList(), listOf(BrowseSuggestion("Wikipedia (top)", 1_000_000_000, map = false, domain = "reference"), BrowseSuggestion("France", 300_000_000, map = true, domain = "maps"))))
        compose.onNodeWithText(NOTHING_TO_BROWSE).assertExists()
        compose.onNodeWithText("A map of your region · 300 MB").assertExists()
        compose.onNodeWithText("Wikipedia (top)").performClick()
        compose.onNodeWithText("France").performClick()
        compose.onNodeWithText("Get packs").performClick()
        compose.onNodeWithText("Or copy .zim files into /packs").assertExists()
        assertThat(calls).containsExactly("get:reference", "get:maps", "get:null").inOrder()
    }
}
