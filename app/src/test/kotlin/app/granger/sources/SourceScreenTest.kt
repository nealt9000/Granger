package app.granger.sources

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan KB: a pack's row with its flavours, in plain words, and the size check before a big Get. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp")
class SourceScreenTest {
    @get:Rule val compose = createComposeRule()

    private val flavours = listOf(
        FlavourChoice("wikipedia_en_top_maxi", "With pictures", 6_990_000_000, chosen = true),
        FlavourChoice("wikipedia_en_top_nopic", "No pictures", 2_750_000_000, chosen = false),
        FlavourChoice("wikipedia_en_top_mini", "Mini (intro paragraphs only)", 300_000_000, chosen = false),
    )
    private val top = ItemRow(
        "wikipedia_en_top_maxi", "Wikipedia: best articles", "English, Russian · Updated March 2026 · CC BY-SA", "",
        6_990_000_000, ItemStatus.NotInstalled, packId = null, flavours = flavours, license = "CC BY-SA 4.0",
    )

    @Test fun aGroupedRowOffersItsFlavoursInPlainWords() {
        val chosen = mutableListOf<String>()
        val got = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(top, onGet = { got += top.id }, onCancel = {}, onRetry = {}, onRemove = {}, onChooseFlavour = { chosen += it })
        }
        compose.onNodeWithContentDescription("With pictures, 7.0 GB").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("English, Russian · Updated March 2026 · CC BY-SA").assertIsDisplayed()
        compose.onNodeWithContentDescription("No pictures, 2.8 GB").assertIsNotSelected().performClick()
        compose.onNodeWithText("Get").performClick()
        assertThat(chosen).containsExactly("wikipedia_en_top_nopic")
        assertThat(got).containsExactly("wikipedia_en_top_maxi")
        // The full licence is a tap away.
        compose.onNodeWithText("Licence: CC BY-SA 4.0").assertDoesNotExist()
        compose.onNodeWithText("Wikipedia: best articles").performClick()
        compose.onNodeWithText("Licence: CC BY-SA 4.0").assertIsDisplayed()
    }

    @Test fun anInstalledFlavourSaysSoAndOffersSwitch() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(
                top.copy(
                    flavours = flavours.map { it.copy(installed = it.label == "No pictures") },
                    installedFlavour = "no pictures", switches = true, packId = "u-top",
                ),
                onGet = { calls += "switch" }, onCancel = {}, onRetry = {}, onRemove = {}, onOpen = { calls += "open" },
            )
        }
        // TalkBack reads the choice, its size and that it's the one on the phone.
        compose.onNodeWithContentDescription("No pictures, 2.8 GB, installed").assertIsDisplayed()
        compose.onNodeWithContentDescription("With pictures, 7.0 GB").assertIsSelected()
        compose.onNodeWithText("Open").performClick()
        // Switch names what it trades, and asks first (Plan KB walkthrough 2).
        compose.onNodeWithText("Switch to With pictures").performClick()
        compose.onNodeWithText(
            "Switch Wikipedia: best articles from No pictures (2.8 GB) to With pictures (7.0 GB)? The No pictures version " +
                "is removed once the new one has downloaded. Your places and bookmarks carry over.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(calls).containsExactly("open").inOrder()
        compose.onNodeWithText("Switch to With pictures").performClick()
        compose.onNodeWithText("Switch").performClick()
        assertThat(calls).containsExactly("open", "switch").inOrder()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    /** With the flavour on the phone chosen (where the radio starts), Switch is there but there's nothing to switch to. */
    @Test fun switchWaitsForAnotherFlavour() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(
                top.copy(
                    id = "wikipedia_en_top_nopic", status = ItemStatus.Installed,
                    flavours = flavours.map { it.copy(installed = it.label == "No pictures", chosen = it.label == "No pictures") },
                    installedFlavour = "no pictures", packId = "u-top",
                ),
                onGet = { calls += "get" }, onCancel = {}, onRetry = {}, onRemove = {}, onOpen = { calls += "open" },
            )
        }
        compose.onNodeWithContentDescription("No pictures, 2.8 GB, installed").assertIsSelected()
        compose.onNodeWithText("Switch").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Open").performClick()
        assertThat(calls).containsExactly("open")
    }

    @Test fun switchTextNamesBothFlavoursAndTheirSizes() {
        val from = FlavourChoice("med_nopic", "No pictures", 1_050_000_000, chosen = false, installed = true)
        val to = FlavourChoice("med_maxi", "With pictures", 1_860_000_000, chosen = true)
        assertThat(switchText("WikiMed", from, to)).isEqualTo(
            "Switch WikiMed from No pictures (1.1 GB) to With pictures (1.9 GB)? The No pictures version is removed once " +
                "the new one has downloaded. Your places and bookmarks carry over.",
        )
    }

    /** Plan KB walkthrough: at 360 dp the title has its own line, the choices a line each, the actions their own line. */
    @Test @Config(qualifiers = "w360dp-h800dp") fun aGroupedRowStacksTitleChoicesAndActionsAt360dp() {
        val long = top.copy(
            title = "Wikipedia: the 50,000 best articles in English, with every picture, every table and every infobox kept",
            flavours = flavours.map { it.copy(installed = it.label == "No pictures") },
            installedFlavour = "no pictures", switches = true, packId = "u-top",
        )
        compose.setContent { CatalogItemRow(long, onGet = {}, onCancel = {}, onRetry = {}, onRemove = {}) }
        val title = compose.onNodeWithText(long.title).assertIsDisplayed()
        val titleBounds = title.getBoundsInRoot()
        // The title has the row's whole width, nothing beside it (it wraps rather than being cut: ThemeShots kiwix-rows).
        assertThat(titleBounds.right - titleBounds.left).isGreaterThan(300.dp)
        val choices = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        choices.assertCountEquals(3)
        val tops = (0 until 3).map { choices[it].getBoundsInRoot().top }
        assertThat(tops).isInStrictOrder()
        // Each choice is one line high, below the title.
        for (i in 0 until 3) choices[i].getBoundsInRoot().let { assertThat(it.bottom - it.top).isLessThan(64.dp) }
        assertThat(tops.first()).isAtLeast(titleBounds.bottom)
        // The actions come last, on their own line, and nothing sits beside the title.
        val actions = compose.onNodeWithTag(SourceTags.ACTIONS).getBoundsInRoot()
        assertThat(actions.top).isAtLeast(choices[2].getBoundsInRoot().bottom)
        assertThat(compose.onNodeWithText("Switch to With pictures").getBoundsInRoot().top).isAtLeast(actions.top)
        assertThat(compose.onNodeWithText("Open").getBoundsInRoot().top).isAtLeast(titleBounds.bottom)
        compose.onNodeWithContentDescription("No pictures, 2.8 GB, installed").assertIsDisplayed()
    }

    @Test fun aSingleFlavourRowStaysCompact() {
        val one = ItemRow("knots", "Knots", "English · Updated 2025 · CC BY", "Tying knots", 60_000_000, ItemStatus.NotInstalled, null)
        compose.setContent { CatalogItemRow(one, onGet = {}, onCancel = {}, onRetry = {}, onRemove = {}) }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(0)
        // Get sits beside the title, as before.
        val title = compose.onNodeWithText("Knots").getBoundsInRoot()
        val get = compose.onNodeWithText("Get").getBoundsInRoot()
        assertThat(get.top).isLessThan(title.bottom)
        compose.onNodeWithText("60 MB · English · Updated 2025 · CC BY").assertIsDisplayed()
    }

    @Test fun theSizeCheckGivesTheFreeSpaceAndASmallerFlavour() {
        val got = mutableListOf<String>()
        var dismissed = false
        val check = SizeCheck(
            "wikipedia_en_all_maxi", "Wikipedia", 110_000_000_000, 41_000_000_000, "Phone",
            smaller = FlavourChoice("wikipedia_en_all_nopic", "No pictures", 48_000_000_000, chosen = false),
        )
        compose.setContent { SizeCheckDialog(check, onGet = { got += it }, onDismiss = { dismissed = true }) }
        compose.onNodeWithText("Wikipedia is 110 GB").assertIsDisplayed()
        compose.onNodeWithText("You have 41 GB free on Phone.").assertIsDisplayed()
        compose.onNodeWithText("That isn't enough room for it.").assertIsDisplayed()
        compose.onNodeWithText("Get no pictures, 48 GB").performClick()
        compose.onNodeWithText("Get 110 GB").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(got).containsExactly("wikipedia_en_all_nopic", "wikipedia_en_all_maxi").inOrder()
        assertThat(dismissed).isTrue()
    }

    @Test fun withoutASmallerFlavourItOffersGetOrCancel() {
        compose.setContent {
            SizeCheckDialog(SizeCheck("survivor", "Survivor Library", 220_000_000_000, 41_000_000_000, "Phone"), onGet = {}, onDismiss = {})
        }
        compose.onNodeWithText("Survivor Library is 220 GB").assertIsDisplayed()
        compose.onNodeWithText("Get 220 GB").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun aFoldedFamilysRowOpensItsList() {
        var opened = false
        val row = ItemRow("family:devdocs", "Developer docs", "243 packs", "", 0L, ItemStatus.NotInstalled, null, opensFamily = "devdocs")
        compose.setContent { FamilyRow(row) { opened = true } }
        compose.onNodeWithText("243 packs").assertIsDisplayed()
        compose.onNodeWithText("Developer docs").performClick()
        assertThat(opened).isTrue()
    }

    // --- Plan WF: Wikipedia as one row ---

    private val wikipedia = ItemRow(
        "wikipedia_en_top_maxi", "Wikipedia", "English · Updated September 2026 · CC BY-SA",
        "The free encyclopedia, from 100 essential articles to all of it.", 6_990_000_000, ItemStatus.NotInstalled, packId = null,
        license = "CC BY-SA 4.0", setId = "wikipedia_en",
        slices = listOf(
            SliceChoice("wikipedia_en_100", "100 essential articles", 333_000_000, chosen = false),
            SliceChoice("wikipedia_en_top", "Best 50,000 articles", 6_990_000_000, chosen = true),
            SliceChoice("wikipedia_en_top1m", "Top 1 million articles", 49_400_000_000, chosen = false),
            SliceChoice("wikipedia_en_all", "Everything", 127_400_000_000, chosen = false),
        ),
        pictures = listOf(
            PictureChoice("maxi", "With pictures", chosen = true),
            PictureChoice("nopic", "No pictures", chosen = false),
            PictureChoice("mini", "Mini (intro paragraphs only)", chosen = false),
        ),
        subjects = "subjects:wikipedia_en:wikipedia_en_top",
    )

    @Test fun theWikipediaRowOffersHowMuchAndPicturesAndASubject() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(
                wikipedia, onGet = { calls += "get" }, onCancel = {}, onRetry = {}, onRemove = {},
                onChooseSlice = { calls += "slice $it" }, onChoosePictures = { calls += "pictures $it" },
                onAddSubject = { calls += "subjects" },
            )
        }
        compose.onNodeWithText("Wikipedia").assertIsDisplayed()
        compose.onNodeWithText("How much").assertIsDisplayed()
        compose.onNodeWithContentDescription("Best 50,000 articles, 7.0 GB").assertIsSelected()
        compose.onNodeWithContentDescription("Top 1 million articles, 49 GB").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("No pictures").performClick()
        compose.onNodeWithText("Add a subject").performClick()
        compose.onNodeWithText("Get").performClick()
        assertThat(calls).containsExactly("slice wikipedia_en_top1m", "pictures nopic", "subjects", "get").inOrder()
    }

    @Test fun aPicturesChoiceTheSliceLacksIsGreyedWithItsReason() {
        compose.setContent {
            CatalogItemRow(
                wikipedia.copy(
                    id = "wikipedia_en_100",
                    slices = wikipedia.slices.map { it.copy(chosen = it.zimName == "wikipedia_en_100") },
                    pictures = wikipedia.pictures.map {
                        if (it.flavour == "maxi") it else it.copy(enabled = false, reason = "Not offered for 100 essential articles")
                    },
                ),
                onGet = {}, onCancel = {}, onRetry = {}, onRemove = {},
            )
        }
        compose.onNodeWithContentDescription("Mini (intro paragraphs only), Not offered for 100 essential articles").assertIsNotEnabled()
    }

    @Test fun aSliceInsideTheOneOnThePhoneReadsSoWithNoGet() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(
                wikipedia.copy(
                    insideOf = "Inside your Top 1 million", packId = "u-top1m",
                    slices = wikipedia.slices.map {
                        when (it.zimName) {
                            "wikipedia_en_top1m" -> it.copy(installed = true)
                            "wikipedia_en_100", "wikipedia_en_top" -> it.copy(inside = "Inside your Top 1 million")
                            else -> it
                        }
                    },
                ),
                onGet = { calls += "get" }, onCancel = {}, onRetry = {}, onRemove = {}, onOpen = { calls += "open" },
            )
        }
        compose.onNodeWithContentDescription("Best 50,000 articles, 7.0 GB, Inside your Top 1 million").assertIsSelected()
        compose.onNodeWithContentDescription("Top 1 million articles, 49 GB, installed").assertIsDisplayed()
        compose.onNodeWithText("Get").assertDoesNotExist()
        compose.onNodeWithText("Open").performClick()
        assertThat(calls).containsExactly("open")
    }

    @Test fun aSubjectSaysWhatItAdds() {
        compose.setContent {
            CatalogItemRow(
                ItemRow("wikipedia_en_medicine_maxi", "WikiMed", "English", "", 1_860_000_000, ItemStatus.NotInstalled, null,
                    adds = "Adds about 69,600 articles to your Best 50,000 (95% new)"),
                onGet = {}, onCancel = {}, onRetry = {}, onRemove = {},
            )
        }
        compose.onNodeWithText("Adds about 69,600 articles to your Best 50,000 (95% new)").assertIsDisplayed()
    }
}
