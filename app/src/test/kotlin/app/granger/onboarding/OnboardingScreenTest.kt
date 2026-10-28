package app.granger.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.granger.core.catalog.UseCase
import app.granger.packEntry
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = OnboardingActions(
        onNext = { calls += "next" }, onBack = { calls += "back" }, onSkip = { calls += "skip" }, onStart = { calls += "start" },
        onRefreshCatalog = { calls += "refresh" }, onChooseStart = { calls += "start:$it" }, onToggleLine = { calls += "line:$it" },
        onRegionQuery = { calls += "query:$it" }, onChooseRegion = { calls += "region:$it" }, onToggleUseCase = { calls += "case:$it" },
        onSeeDomain = { calls += "see:$it" }, onWifiOnly = { calls += "wifi:$it" }, onChooseVolume = { calls += "volume:$it" },
    )

    private val med = packEntry("wikipedia_en_medicine_nopic", size = 1_000).copy(title = "WikiMed (no pictures)")
    private val peru = packEntry("maps_en_peru", size = 500).copy(family = "maps", title = "Peru")
    private val line = Line(med.id, med.title, listOf(med), installed = false)
    private val efficient = StartChoice("efficient", "Efficient start", "The essentials.", listOf(line))
    private val internal = volume("/internal", "Internal storage", free = 10_000, total = 64_000)
    private val phone = PhoneUi("A library phone, with about 7 GB of memory.", "Research needs 6 GB.", listOf(internal), 1_000, 1_375)

    private fun show(ui: OnboardingUi) = compose.setContent { OnboardingContent(ui, actions) }

    @Test fun welcomeHasTheBrandGetStartedAndSkip() {
        show(OnboardingUi(step = Step.WELCOME))
        compose.onNodeWithContentDescription("Granger").assertIsDisplayed()
        compose.onNodeWithText("The world’s knowledge.\nYours to keep.").assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip").performClick()
        assertThat(calls).containsExactly("next", "skip").inOrder()
    }

    @Test fun thisPhoneShowsTheTierStorageAndSizes() {
        show(OnboardingUi(step = Step.PHONE, phone = phone))
        compose.onNodeWithText("A library phone, with about 7 GB of memory.").assertIsDisplayed()
        compose.onNodeWithText("Research needs 6 GB.").assertIsDisplayed()
        compose.onNodeWithText("Internal storage: 10 KB free of 64 KB").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        assertThat(calls).containsExactly("back")
    }

    @Test fun pickAStartShowsTheFetchWhileTheCatalogArrives() {
        show(OnboardingUi(step = Step.START, noCatalog = true, refreshing = true))
        compose.onNodeWithText("Getting the list of packs…").assertIsDisplayed()
        compose.onNodeWithText("Refresh the catalog to see the choices.").assertDoesNotExist()
    }

    @Test fun pickAStartOffersTryAgainWhenTheFetchFailed() {
        show(OnboardingUi(step = Step.START, noCatalog = true, catalogFailed = true))
        compose.onNodeWithText("Couldn't reach the catalog. Check the connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertThat(calls).containsExactly("refresh")
    }

    @Test fun pickAStartListsChoicesAndLinesWithTheTotal() {
        show(OnboardingUi(step = Step.START, noCatalog = false, phone = phone, starts = listOf(efficient), startId = "efficient"))
        compose.onNodeWithText("Efficient start · 1.0 KB").assertIsSelected()
        compose.onNodeWithText("Neither, I'll choose later").performClick()
        compose.onNodeWithText("WikiMed (no pictures)").performClick()
        compose.onNodeWithText("1.0 KB of 10 KB free").assertIsDisplayed()
        assertThat(calls).containsExactly("start:null", "line:wikipedia_en_medicine_nopic").inOrder()
    }

    @Test fun yourSituationSearchesRegionsAndTogglesChips() {
        val regions = Regions(listOf(Region(peru, installed = false)), emptyList())
        val addition = Addition(UseCase("medical", "Medical work", "Clinical.", listOf("x")), listOf(line))
        show(OnboardingUi(step = Step.SITUATION, noCatalog = false, regions = regions, additions = listOf(addition)))
        compose.onNodeWithText("Country or area").performTextInput("pe")
        compose.onNodeWithText("Peru · 500 B").performClick()
        compose.onNodeWithText("Medical work").performClick()
        compose.onNodeWithText("Clinical. Adds 1.0 KB · 1 item.").assertIsDisplayed()
        compose.onNodeWithText("See").performClick()
        assertThat(calls).containsExactly("query:pe", "region:maps_en_peru", "case:medical", "see:medicine").inOrder()
    }

    @Test fun reviewListsGroupsTheTotalWifiAndStart() {
        val review = Review(listOf(ReviewGroup("Efficient start", listOf(line)), ReviewGroup("Your region", listOf(Line(peru.id, "Peru", listOf(peru), false)))), freeBytes = 10_000)
        show(OnboardingUi(step = Step.REVIEW, noCatalog = false, phone = phone, review = review, wifiOnly = true))
        compose.onNodeWithText("Your region").assertIsDisplayed()
        compose.onNodeWithText("Peru").assertIsDisplayed()
        compose.onNodeWithText("1.5 KB of 10 KB free").assertIsDisplayed()
        compose.onNodeWithText("Download on Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithText("Start").performClick()
        assertThat(calls).containsExactly("start")
    }

    @Test fun anEmptyReviewFinishesAndANoticeShows() {
        show(OnboardingUi(step = Step.REVIEW, noCatalog = false, phone = phone, notice = "Not enough space on Internal storage: free up 5 KB or untick something."))
        compose.onNodeWithText("Nothing to download yet. Home and the Library are ready; the Kiwix screen has everything else.").assertIsDisplayed()
        compose.onNodeWithText("Not enough space on Internal storage: free up 5 KB or untick something.").assertIsDisplayed()
        compose.onNodeWithText("Finish").performClick()
        assertThat(calls).containsExactly("start")
    }
}
