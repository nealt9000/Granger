package app.granger.browse

import app.granger.UiText
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
        onFiles = { calls += "files:$it" },
    )

    private val wikipedia = PackItem(pack("w", "Wikipedia").copy(sizeBytes = 1_200_000_000), AskReach.MEANING)
    private val gone = PackItem(pack("g", "Gone", status = PackStatus.MISSING), AskReach.KEYWORDS)
    private val iceland = PackItem(pack("i", "Iceland").copy(sizeBytes = 80_000_000))

    private fun show(ui: BrowseUi) = compose.setContent { BrowseContent(ui, "/packs", downloadsBadge = 0, actions) }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left

    @Test fun eachPackIsARowWithItsSizeAndAskLineAndOpens() {
        show(BrowseUi(listOf(BrowseSection("reference", UiText.Raw("General reference"), listOf(gone, wikipedia)), BrowseSection(MAPS_SECTION, UiText.Raw("Maps"), listOf(iceland)))))
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

    @Test fun aSectionShowsFourPacksUntilShowAll() {
        val six = (1..6).map { PackItem(pack("p$it", "Pack $it"), AskReach.KEYWORDS) }
        show(BrowseUi(listOf(BrowseSection("science", UiText.Raw("Science & math"), six))))
        compose.onNodeWithText("Pack 4").assertExists()
        compose.onNodeWithText("Pack 5").assertDoesNotExist()
        compose.onNodeWithText("Show all 6").performClick()
        compose.onNodeWithText("Pack 6").assertExists()
        compose.onNodeWithText("Show fewer").performClick()
        compose.onNodeWithText("Pack 5").assertDoesNotExist()
    }

    @Test fun fourPacksOrFewerHaveNoShowAll() {
        val four = (1..4).map { PackItem(pack("p$it", "Pack $it"), AskReach.KEYWORDS) }
        show(BrowseUi(listOf(BrowseSection("science", UiText.Raw("Science & math"), four))))
        compose.onNodeWithText("Show all", substring = true).assertDoesNotExist()
    }

    @Test fun aLongPressRemovesThePackAfterAsking() {
        show(BrowseUi(listOf(BrowseSection("reference", UiText.Raw("General reference"), listOf(wikipedia)))))
        compose.onNodeWithText("Wikipedia").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from phone").performClick()
        compose.onNodeWithText("Remove Wikipedia?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        assertThat(calls).containsExactly("remove:w")
    }

    /** Plan KB2 Task 4.6: a pack holding PDFs or EPUBs offers Files in this pack on its long press; one without doesn't. */
    @Test fun aLongPressOffersTheFilesInThePackWhenItHasSome() {
        show(BrowseUi(listOf(BrowseSection("reference", UiText.Raw("General reference"), listOf(wikipedia.copy(files = 12), iceland)))))
        compose.onNodeWithText("Wikipedia").performTouchInput { longClick() }
        compose.onNodeWithText("Files in this pack (12)").performClick()
        assertThat(calls).containsExactly("files:w")
        compose.onNodeWithText("Iceland").performTouchInput { longClick() }
        compose.onNodeWithText("Files in this pack", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Remove from phone").assertExists()
    }

    @Test fun theHeaderIsGetMoreThenMoreWithDownloadsAndReadiness() {
        show(BrowseUi(listOf(BrowseSection("reference", UiText.Raw("General reference"), listOf(wikipedia)))))
        assertThat(compose.onNodeWithContentDescription("Get more").left()).isLessThan(compose.onNodeWithContentDescription("More").left())
        compose.onNodeWithContentDescription("Get more").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Downloads").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Readiness").performClick()
        assertThat(calls).containsExactly("get:null", "downloads", "readiness").inOrder()
    }

    /** Plan EX Task 4: Sources leads with a Books row counting every book collection, and has Back to Explore. */
    @Test fun theBooksRowOpensTheLibraryAndSourcesHasBack() {
        var back = 0
        var books = 0
        compose.setContent {
            BrowseContent(
                BrowseUi(listOf(BrowseSection("reference", UiText.Raw("General reference"), listOf(wikipedia))), bookCollections = 4),
                "/packs", downloadsBadge = 0, BrowseActions(onBooks = { books++ }, onBack = { back++ }),
            )
        }
        compose.onNodeWithText("Your sources").assertExists()
        compose.onNodeWithText("4 collections").assertExists()
        compose.onNodeWithText("Your books").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(books to back).isEqualTo(1 to 1)
    }

    @Test fun bookCollectionsCountEachPackAndEachOfGrangersOnce() {
        val books = listOf(
            app.granger.book("gc-hamlet"), app.granger.book("gc-iliad"), app.granger.book("gc-es-quijote"),
            app.granger.book("asb-en-1"), app.granger.book("asb-sw-2"),
        )
        val gutenberg = pack("gp", "Gutenberg poetry").copy(name = "gutenberg_en_lcc-p")
        assertThat(bookCollectionCount(listOf(gutenberg, pack("w", "Wikipedia")), books)).isEqualTo(5)
    }

    @Test fun anEmptyBrowseSuggestsWhatToGetFirst() {
        show(BrowseUi(emptyList(), listOf(BrowseSuggestion("Wikipedia (top)", 1_000_000_000, map = false, domain = "reference"), BrowseSuggestion("France", 300_000_000, map = true, domain = "maps"))))
        compose.onNodeWithText("Nothing to browse yet").assertExists()
        compose.onNodeWithText("A map of your region · 300 MB").assertExists()
        compose.onNodeWithText("Wikipedia (top)").performClick()
        compose.onNodeWithText("France").performClick()
        compose.onNodeWithText("Get packs").performClick()
        compose.onNodeWithText("Or copy .zim files into /packs").assertExists()
        assertThat(calls).containsExactly("get:reference", "get:maps", "get:null").inOrder()
    }
}
