package app.granger.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import app.granger.core.catalog.UseCase
import app.granger.packEntry
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import app.granger.UiText
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
        onRefreshCatalog = { calls += "refresh" }, onChooseStart = { calls += "start:$it" }, onToggleLine = { calls += "line:$it" }, onToggleAsk = { calls += "ask" },
        onRegionQuery = { calls += "query:$it" }, onChooseRegion = { calls += "region:$it" }, onToggleUseCase = { calls += "case:$it" },
        onSeeDomain = { calls += "see:$it" }, onWifiOnly = { calls += "wifi:$it" }, onChooseVolume = { calls += "volume:$it" },
    )

    private val med = packEntry("wikipedia_en_medicine_nopic", size = 1_000).copy(title = "WikiMed (no pictures)")
    private val peru = packEntry("maps_en_peru", size = 500).copy(family = "maps", title = "Peru")
    private val line = Line(med.id, UiText.Raw(med.title), listOf(med), installed = false)
    private val efficient = StartChoice("efficient", "Efficient start", UiText.Raw("The essentials."), listOf(line))
    private val internal = volume("/internal", "Internal storage", free = 10_000, total = 64_000)
    private val phone = PhoneUi(UiText.Raw("A library phone, with about 7 GB of memory."), UiText.Raw("Ask needs 6 GB."), listOf(internal), "Efficient start", 1_000, 1_375)

    private fun show(ui: OnboardingUi) = compose.setContent { OnboardingContent(ui, actions) }

    @Test fun welcomeHasTheBrandGetStartedAndSkip() {
        show(OnboardingUi(step = Step.WELCOME))
        compose.onNodeWithContentDescription("Granger").assertIsDisplayed()
        compose.onNodeWithText("Your door to knowledge.\nAlways open.").assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip").performClick()
        assertThat(calls).containsExactly("next", "skip").inOrder()
    }

    @Test fun onlineWelcomeHoldsTheChoiceAndThePrivacyLine() {
        // Plan OB call 1: no Skip; the two choices, the privacy line under them.
        val acts = OnboardingActions(onReadNow = { calls += "read" }, onOutfit = { calls += "outfit" })
        compose.setContent { OnboardingContent(OnboardingUi(step = Step.WELCOME, online = true, intro = UiText.Res(app.granger.R.string.onboarding_intro_online)), acts) }
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithTag("privacy-line").assertIsDisplayed()
        compose.onNodeWithText("An encyclopedia to wander", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("choose-read-now").performClick()
        compose.onNodeWithTag("choose-outfit").performClick()
        assertThat(calls).containsExactly("read", "outfit").inOrder()
    }

    @Test fun outfitPageShowsTheSpaceSectionsAndReview() {
        val acts = OnboardingActions(onBack = { calls += "back" }, onNext = { calls += "next" }, onNotNow = { calls += "notnow" })
        compose.setContent { OnboardingContent(OnboardingUi(step = Step.OUTFIT, online = true, noCatalog = false, phone = phone, starts = listOf(efficient), startId = "efficient"), acts) }
        compose.onNodeWithText("10 KB free on this phone").assertIsDisplayed()
        compose.onNodeWithText("A START").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("outfit-review").performClick()
        assertThat(calls).containsExactly("notnow", "back", "next").inOrder()
    }

    @Test fun pickAStartOffersAskUnderTheChoices() {
        val ask = Line(OnboardingModel.ASK, UiText.Res(app.granger.R.string.onboarding_ask_line), listOf(app.granger.modelEntry("e2b", size = 2_200_000_000)), installed = false)
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, startId = null, askLine = ask, askChosen = true))
        compose.onNodeWithText("Ask (Preview) · 2.2 GB").assertIsDisplayed().assertIsOn().performClick()
        compose.onNodeWithText("The librarian answers questions from your library, on this phone. It's a preview: it can be wrong.").assertIsDisplayed()
        assertThat(calls).containsExactly("ask")
    }

    @Test fun pickAStartShowsTheFetchWhileTheCatalogArrives() {
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = true, refreshing = true))
        compose.onNodeWithText("Getting the list of packs…").assertIsDisplayed()
        compose.onNodeWithText("Refresh the catalog to see the choices.").assertDoesNotExist()
    }

    @Test fun pickAStartOffersTryAgainWhenTheFetchFailed() {
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = true, catalogFailed = true))
        compose.onNodeWithText("Couldn't reach the catalog. Check the connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertThat(calls).containsExactly("refresh")
    }

    @Test fun pickAStartListsChoicesAndLinesWithTheTotal() {
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, phone = phone, starts = listOf(efficient), startId = "efficient"))
        compose.onNodeWithText("Efficient start · 1.0 KB").assertIsSelected()
        compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("WikiMed (no pictures)").performClick()
        assertThat(calls).containsExactly("start:null", "line:wikipedia_en_medicine_nopic").inOrder()
    }

    @Test fun yourRegionSearchesAndPicks() {
        val regions = Regions(listOf(Region(peru, installed = false)), emptyList())
        // The query is held as the view model holds it, so the field shows what was typed when picking takes its focus.
        var ui by mutableStateOf(OnboardingUi(step = Step.OUTFIT, noCatalog = false, regions = regions))
        val typing = OnboardingActions(onRegionQuery = { calls += "query:$it"; ui = ui.copy(regionQuery = it) }, onChooseRegion = actions.onChooseRegion)
        compose.setContent { OnboardingContent(ui, typing) }
        compose.onNodeWithTag("region-choose").performClick()
        compose.onNodeWithText("Country or area").performTextInput("pe")
        compose.onNodeWithText("Peru · 500 B").performClick()
        compose.onNodeWithText("Medical work").assertDoesNotExist()
        assertThat(calls).containsExactly("query:pe", "region:maps_en_peru").inOrder()
    }

    /** Plan U3c walkthrough: the keyboard covered the result and Next; picking one puts it away. */
    @Test fun pickingARegionTakesFocusOffTheSearch() {
        val regions = Regions(listOf(Region(peru, installed = false)), emptyList())
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, regions = regions, regionQuery = "pe"))
        compose.onNodeWithTag("region-choose").performClick()
        val field = compose.onNodeWithText("Country or area")
        field.performClick()
        field.assertIsFocused()
        compose.onNodeWithText("Peru · 500 B").performClick()
        // Picking folds the field away with the list, so nothing keeps the keyboard up.
        field.assertDoesNotExist()
        // Picking folds the list back into its row; Review stays in view (Plan OB call 2).
        compose.onNodeWithTag("region-choose").assertIsDisplayed()
        compose.onNodeWithText("Review").assertIsDisplayed()
        assertThat(calls).containsExactly("region:maps_en_peru")
    }

    @Test fun yourRegionShowsTheSuggestionAndNoRegionClearsIt() {
        val regions = Regions(listOf(Region(peru, installed = false)), emptyList())
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, regions = regions, region = Region(peru, installed = false), regionSuggested = true))
        compose.onNodeWithText("Peru · 500 B").assertIsDisplayed()
        compose.onNodeWithTag("region-choose").performClick()
        compose.onNodeWithText("Suggested from this phone").assertIsDisplayed()
        compose.onNodeWithText("Peru · 500 B").assertIsDisplayed()
        compose.onNodeWithText("No region").performClick()
        assertThat(calls).containsExactly("region:null")
    }

    @Test fun yourRegionOffersABigSuggestionFirstUnpicked() {
        val regions = Regions(listOf(Region(peru, installed = false)), emptyList())
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, regions = regions, suggestion = Region(peru, installed = false)))
        compose.onNodeWithTag("region-choose").performClick()
        compose.onNodeWithText("Suggested from this phone").assertIsDisplayed()
        compose.onNodeWithText("Other countries").assertIsDisplayed()
        compose.onAllNodesWithText("Peru · 500 B")[0].performClick()
        assertThat(calls).containsExactly("region:maps_en_peru")
    }

    @Test fun usesTogglesChipsOnItsOwnScreen() {
        val addition = Addition(UseCase("medical", "Medical work", "Clinical.", listOf("x")), listOf(line))
        show(OnboardingUi(step = Step.OUTFIT, noCatalog = false, additions = listOf(addition)))
        compose.onNodeWithText("Country or area").assertDoesNotExist()
        compose.onNodeWithText("Medical work").performClick()
        compose.onNodeWithText("Clinical. Adds 1.0 KB · 1 item.").assertIsDisplayed()
        compose.onNodeWithText("See").performClick()
        assertThat(calls).containsExactly("case:medical", "see:medicine").inOrder()
    }

    @Test fun everyStepOpensAtItsTop() {
        val many = (1..40).map { Region(packEntry("maps_en_c$it", size = 100).copy(family = "maps", title = "Country $it"), installed = false) }
        var ui by mutableStateOf(OnboardingUi(step = Step.OUTFIT, noCatalog = false, phone = phone, starts = listOf(efficient), startId = "efficient", regions = Regions(many, many.take(10))))
        compose.setContent { OnboardingContent(ui, actions) }
        compose.onNodeWithTag("onboarding-list").performScrollToIndex(6)
        ui = ui.copy(step = Step.REVIEW)
        compose.onNodeWithText("Nothing to download yet", substring = true).assertIsDisplayed()
        ui = ui.copy(step = Step.OUTFIT)
        compose.onNodeWithText("10 KB free on this phone").assertIsDisplayed()
    }

    @Test fun reviewListsGroupsTheTotalWifiAndStart() {
        val review = Review(listOf(ReviewGroup(UiText.Raw("Efficient start"), listOf(line)), ReviewGroup(UiText.Res(app.granger.R.string.onboarding_your_region), listOf(Line(peru.id, UiText.Raw("Peru"), listOf(peru), false)))), freeBytes = 10_000)
        show(OnboardingUi(step = Step.REVIEW, noCatalog = false, phone = phone, review = review, wifiOnly = true))
        compose.onNodeWithText("Your region").assertIsDisplayed()
        compose.onNodeWithText("Peru").assertIsDisplayed()
        compose.onNodeWithText("1.5 KB of 10 KB free").assertIsDisplayed()
        compose.onNodeWithText("Download on Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithText("Start").performClick()
        assertThat(calls).containsExactly("start")
    }

    @Test fun anEmptyReviewFinishesAndANoticeShows() {
        show(OnboardingUi(step = Step.REVIEW, noCatalog = false, phone = phone, notice = UiText.Res(app.granger.R.string.onboarding_no_space, "Internal storage", "5 KB")))
        compose.onNodeWithText("Nothing to download yet. Read online, and keep what you like as you go; Outfitting has everything else.").assertIsDisplayed()
        compose.onNodeWithText("Not enough space on Internal storage: free up 5 KB or untick something.").assertIsDisplayed()
        compose.onNodeWithText("Finish").performClick()
        assertThat(calls).containsExactly("start")
    }
}
