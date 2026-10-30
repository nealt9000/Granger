package app.granger.sources

import androidx.compose.ui.test.assertIsDisplayed
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
        FlavourChoice("wikipedia_en_top_mini", "Mini", 300_000_000, chosen = false),
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
        compose.onNodeWithText("With pictures").assertIsDisplayed()
        compose.onNodeWithText("7.0 GB").assertIsDisplayed()
        compose.onNodeWithText("English, Russian · Updated March 2026 · CC BY-SA").assertIsDisplayed()
        compose.onNodeWithText("No pictures").performClick()
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
                top.copy(installedFlavour = "no pictures", switches = true, packId = "u-top"),
                onGet = { calls += "switch" }, onCancel = {}, onRetry = {}, onRemove = {}, onOpen = { calls += "open" },
            )
        }
        compose.onNodeWithText("Installed: no pictures").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Switch").performClick()
        assertThat(calls).containsExactly("open", "switch").inOrder()
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
}
