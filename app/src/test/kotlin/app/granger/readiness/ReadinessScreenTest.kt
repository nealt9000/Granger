package app.granger.readiness

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.PackEntry
import app.granger.SHA_A
import app.granger.formatBytes
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadinessScreenTest {
    @get:Rule val compose = createComposeRule()

    private val medline = PackEntry(
        id = "medlineplus.gov_en_all", title = "MedlinePlus", category = "Medical", language = "eng", sizeBytes = 370_000_000, sha256 = SHA_A,
        urls = listOf("https://download.kiwix.org/zim/medline.zim"), snapshotDate = "2026-10-10", license = "CC BY-SA 4.0",
        hasFulltextIndex = true, zimUuid = "u-medline", zimName = "medlineplus.gov_en_all", family = "medlineplus.gov", domain = "medicine",
    )

    private fun ui(report: ReadinessReport) = ReadinessUi(loading = false, tier = DeviceTier.ASK, totalRamBytes = 8_000_000_000, report = report)

    private val report = ReadinessReport(
        domains = listOf(
            DomainCoverage("medicine", "Medicine & first aid", Coverage.NONE, 0, 370_000_000, 4_000_000_000, listOf(medline)),
            DomainCoverage("reference", "General reference", Coverage.BASIC, 2_750_000_000, 11_900_000_000, 60_000_000_000, emptyList()),
        ),
        signals = listOf(
            Signal.EmptyDomain("medicine", medline, "Nothing yet for Medicine & first aid. Get MedlinePlus, 370.0 MB."),
            Signal.Stale("reference", "wikipedia_en_top_nopic", "Wikipedia is from 2025-01-10; a newer one is in the catalog."),
            Signal.Unsearchable("u-ifixit", "iFixit can't be searched: the pack has no index."),
            Signal.Unsearchable("u-gutenberg", "Gutenberg can't be searched: the pack has no index."),
        ),
        storage = listOf(StorageRow(Source.KIWIX, 2_750_000_000, 1), StorageRow(Source.GRANGER, 0, 0), StorageRow(Source.PHONE_MEDIA, 0, 0, referencedOnly = true)),
        needsCatalogRefresh = false,
    )

    /** The list is lazy: a row has to be scrolled into view before it exists in the tree. */
    private fun scrollTo(text: String) = compose.onNodeWithTag("readiness-list").performScrollToNode(hasText(text))

    @Test fun tappingADomainOrASignalOpensTheKiwixScreenAtIt() {
        val opened = mutableListOf<String?>()
        compose.setContent { ReadinessContent(ui(report), onBack = {}, onOpenKiwix = { opened += it }, onVerify = {}, onRepair = {}) }
        scrollTo("Medicine & first aid")
        compose.onNodeWithText("Nothing yet · the essentials are ${formatBytes(370_000_000)}").assertExists()
        compose.onNodeWithText("Medicine & first aid").performClick()
        assertThat(opened).containsExactly("medicine")
        scrollTo("General reference")
        compose.onNodeWithText("Basic · ${formatBytes(2_750_000_000)} of ${formatBytes(60_000_000_000)}").assertExists()

        // Three signals show, the fourth behind Show all.
        scrollTo("Nothing yet for Medicine & first aid. Get MedlinePlus, 370.0 MB.")
        scrollTo("Show all 4")
        compose.onNodeWithText("Show all 4").performClick()
        scrollTo("Gutenberg can't be searched: the pack has no index.")
        compose.onNodeWithText("Show all 4").assertDoesNotExist()
        // The stale signal's See opens the Kiwix screen at its domain.
        scrollTo("Wikipedia is from 2025-01-10; a newer one is in the catalog.")
        compose.onAllNodesWithText("See")[1].performClick()
        assertThat(opened).containsExactly("medicine", "reference").inOrder()
    }

    @Test fun storageBySourceHidesEmptyRowsAndMarksPhoneMedia() {
        compose.setContent { ReadinessContent(ui(report), onBack = {}, onOpenKiwix = {}, onVerify = {}, onRepair = {}) }
        scrollTo("Kiwix")
        compose.onNodeWithText("${formatBytes(2_750_000_000)} · 1 item").assertExists()
        compose.onNodeWithText("Granger").assertDoesNotExist()
        scrollTo("Music & audiobooks on the phone")
        compose.onNodeWithText("Not stored by Granger").assertExists()
        compose.onNodeWithText("Files").assertDoesNotExist()
        compose.onNodeWithText("Packs").assertDoesNotExist()
    }

    @Test fun aSourceOpensToItsOwnFiles() {
        fun row(key: String, title: String, source: Source, status: CheckStatus = CheckStatus.VERIFIED) = CheckRow(
            key, title, isModel = source == Source.MODELS, snapshotDate = "", sizeBytes = 1_000, status = status, verifiedAt = null,
            progress = null, canVerify = true, canRepair = status == CheckStatus.MISSING, source = source,
        )
        val withFiles = ui(report).copy(
            models = listOf(row("e2b", "Gemma E2B", Source.MODELS)),
            packs = listOf(row("top", "Wikipedia Top", Source.KIWIX), row("gone", "MedlinePlus", Source.KIWIX, CheckStatus.MISSING)),
            report = report.copy(storage = report.storage + StorageRow(Source.MODELS, 2_000_000_000, 1)),
        )
        compose.setContent { ReadinessContent(withFiles, onBack = {}, onOpenKiwix = {}, onVerify = {}, onRepair = {}) }
        scrollTo("Kiwix")
        compose.onNodeWithText("${formatBytes(2_750_000_000)} · 1 item · 1 needs attention").assertExists()
        // Closed, the files aren't listed.
        compose.onNodeWithText("Wikipedia Top").assertDoesNotExist()
        compose.onNodeWithText("Kiwix").performClick()
        scrollTo("Wikipedia Top")
        scrollTo("MedlinePlus")
        compose.onNodeWithText("Missing: the file isn't there").assertExists()
        // Another source's files stay behind its own row.
        compose.onNodeWithText("Gemma E2B").assertDoesNotExist()
        scrollTo("Kiwix")
        compose.onNodeWithText("Kiwix").performClick()
        compose.onNodeWithText("Wikipedia Top").assertDoesNotExist()
    }

    @Test fun anOlderCatalogShowsTheRefreshLine() {
        compose.setContent { ReadinessContent(ui(ReadinessReport.EMPTY), onBack = {}, onOpenKiwix = {}, onVerify = {}, onRepair = {}) }
        compose.onNodeWithText("What you hold offline").assertExists()
        compose.onNodeWithText("Refresh the catalog to see your coverage.").assertExists()
    }
}
