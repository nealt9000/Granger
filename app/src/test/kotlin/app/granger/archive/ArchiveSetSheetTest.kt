package app.granger.archive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.granger.core.archive.ArchiveFile
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.Offer
import app.granger.core.archive.Part
import app.granger.core.archive.TextKind
import app.granger.formatBytes
import app.granger.sources.ItemStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveSetSheetTest {
    @get:Rule val compose = createComposeRule()

    private val item = ArchiveItem("run", "Batman (1986)", "Frank Miller", "1986", 1986, emptyList(), "", "", "", false, emptyList())

    private fun file(n: Int) = ArchiveFile("run/$n.cbr", "Comic Book RAR", 50_000_000L, "abc", null, "original", false)

    private fun sheet(count: Int, statuses: Map<Int, ItemStatus> = emptyMap(), status: ItemStatus = ItemStatus.NotInstalled): ItemSheet {
        val parts = (1..count).map { Part(file(it), it, "Issue %02d".format(it)) }
        val offer = Offer.Download(parts.first().file, TextKind.COMIC, parts = parts)
        return ItemSheet(
            identifier = "run", loading = false, item = item, offer = offer, sizeBytes = offer.totalBytes, error = null, status = status,
            parts = parts.map { PartRow(it.number, it.title, it.sizeBytes, statuses[it.number] ?: ItemStatus.NotInstalled) },
        )
    }

    @Test fun aSetSaysHowManyPartsAndGetsThemAll() {
        var got = 0
        compose.setContent { ArchiveItemSheet(sheet(4), null, { got++ }, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Comic: its pages, to zoom · 4 parts · ${formatBytes(200_000_000L)}").assertExists()
        compose.onNodeWithText("Get all · ${formatBytes(200_000_000L)}").performClick()
        assertThat(got).isEqualTo(1)
    }

    @Test fun choosePartsListsEachWithWhatItCanDo() {
        val gotParts = mutableListOf<Int>()
        val removed = mutableListOf<Int>()
        val statuses = mapOf(2 to ItemStatus.Installed, 3 to ItemStatus.Downloading(0.5f))
        compose.setContent {
            ArchiveItemSheet(
                sheet(4, statuses, ItemStatus.Downloading(0.4f)), null, {}, {}, {}, {}, {}, {},
                onGetPart = { gotParts += it }, onRemovePart = { removed += it },
            )
        }
        compose.onNodeWithText("Choose parts").performClick()
        compose.onNodeWithText("4 parts").assertExists()
        compose.onNodeWithText("${formatBytes(50_000_000L)} · in your library").assertExists()
        compose.onNodeWithText("${formatBytes(50_000_000L)} · downloading, 50%").assertExists()
        compose.onNodeWithTag(ArchiveSheetTags.PART_FILTER).assertDoesNotExist()      // 4 parts need no filter
        compose.onNodeWithText("Remove").performClick()
        assertThat(removed).containsExactly(2)
        compose.onAllNodesWithTextGet("Get").first().performClick()
        assertThat(gotParts).containsExactly(1)
    }

    @Test fun aLongSetHasAFilter() {
        compose.setContent { ArchiveItemSheet(sheet(30), null, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Choose parts").performClick()
        compose.onNodeWithTag(ArchiveSheetTags.PART_FILTER).performTextInput("Issue 2")
        compose.onNodeWithText("Issue 21").assertExists()
        compose.onNodeWithText("Issue 13").assertDoesNotExist()
    }

    @Test fun filterAndStatusText() {
        val rows = listOf(PartRow(1, "1889-02 001-3", 10, ItemStatus.NotInstalled), PartRow(2, "Special Issue", 10, ItemStatus.NotInstalled))
        assertThat(filterParts(rows, " special ").map { it.number }).containsExactly(2)
        assertThat(filterParts(rows, "").size).isEqualTo(2)
        assertThat(partStatusText(PartRow(1, "x", 1_000, ItemStatus.Failed("timed out")))).isEqualTo("${formatBytes(1_000)} · stopped: timed out")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextGet(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = false)).fetchSemanticsNodes().indices.map {
            onAllNodes(androidx.compose.ui.test.hasText(text, substring = false))[it]
        }
}
