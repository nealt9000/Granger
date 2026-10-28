package app.granger.library

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.core.library.CollectionRow
import app.granger.core.library.ShelfCollection
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val wikipedia = CollectionRow(
        ShelfCollection("c:wikipedia", "Wikipedia", "literature", userCreated = false, defaultKey = "wikipedia"), listOf(pack("w")),
    )

    private fun show(ui: LibraryUi, badge: Int = 0) = compose.setContent {
        LibraryContent(
            ui, "/packs", SnackbarHostState(),
            onOpenCollection = { calls += "collection:$it" }, onOpenMusic = {}, onOpenAudiobooks = {},
            onSearch = { calls += "search" }, onReadiness = { calls += "readiness" }, downloadsBadge = badge,
            onDownloads = { calls += "downloads" }, onGetMore = { calls += "more" }, onImport = {},
        )
    }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left

    @Test fun theHeaderIsSearchDownloadsGetMoreReadinessMore() {
        show(LibraryUi(emptyList(), emptyLibrary = false))
        val lefts = listOf("Search", "Downloads", "Get more", "Readiness", "More").map { compose.onNodeWithContentDescription(it).left() }
        assertThat(lefts).isInStrictOrder()
        // History is the rail's.
        compose.onNodeWithContentDescription("History").assertDoesNotExist()
        for (d in listOf("Search", "Downloads", "Get more", "Readiness")) compose.onNodeWithContentDescription(d).performClick()
        assertThat(calls).containsExactly("search", "downloads", "more", "readiness").inOrder()
    }

    @Test fun theDownloadsBadgeCountsWhatsComing() {
        show(LibraryUi(emptyList(), emptyLibrary = false), badge = 3)
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test fun eachKindOpensToItsCollections() {
        show(LibraryUi(listOf(KindRow(REFERENCE_KIND, "Encyclopedias & reference", "literature", listOf(wikipedia))), emptyLibrary = false))
        compose.onNodeWithText("Wikipedia").performClick()
        compose.onNodeWithText("Encyclopedias & reference").performClick()
        compose.onNodeWithText("Wikipedia").assertDoesNotExist()
        assertThat(calls).containsExactly("collection:c:wikipedia")
    }

    @Test fun anEmptyLibraryPointsToGetMore() {
        show(LibraryUi(emptyList(), emptyLibrary = true))
        compose.onNodeWithText("Get packs and books →").performClick()
        assertThat(calls).containsExactly("more")
    }
}
