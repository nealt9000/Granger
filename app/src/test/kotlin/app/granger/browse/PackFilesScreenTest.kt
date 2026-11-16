package app.granger.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.FakeLibrary
import app.granger.FakeZimArchive
import app.granger.core.library.PackFile
import app.granger.core.zim.ZimEntry
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan KB2 Task 4.6: Files in this pack lists a website pack's documents by name and opens one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp")
class PackFilesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFilesAreListedByNameWithTheirSizeAndOpen() {
        val library = FakeLibrary()
        library.packList.value = listOf(pack("ready", "Ready.gov"))
        library.archives["ready"] = FakeZimArchive(
            "ready",
            listed = listOf(
                ZimEntry("A/index.html", "Home", "text/html", 10),
                ZimEntry("I/files/Flood_Safety%20Tips.pdf", "", "application/pdf", 2_000_000),
                ZimEntry("I/files/beat_the_heat.epub", "", "application/epub+zip", 340_000),
            ),
        )
        val opened = mutableListOf<Pair<String, String>>()
        compose.setContent {
            PackFilesScreen(PackFilesViewModel("ready", library, io = Dispatchers.Main), onBack = {}, onOpen = { p, t -> opened += p to t })
        }
        compose.waitForIdle()
        compose.onNodeWithText("Ready.gov").assertExists()
        compose.onNodeWithText("Files in this pack").assertExists()
        compose.onNodeWithText("PDF · 2.0 MB").assertExists()
        compose.onNodeWithText("EPUB · 340 KB").assertExists()
        compose.onNodeWithText("Flood Safety Tips").performClick()
        assertThat(opened).containsExactly("I/files/Flood_Safety%20Tips.pdf" to "Flood Safety Tips")
    }

    @Test fun aPackThatCantBeListedSaysSo() {
        val library = FakeLibrary()
        library.packList.value = listOf(pack("x", "Broken"))
        library.archives["x"] = FakeZimArchive("x")
        compose.setContent { PackFilesScreen(PackFilesViewModel("x", library, io = Dispatchers.Main), onBack = {}, onOpen = { _, _ -> }) }
        compose.waitForIdle()
        compose.onNodeWithText("No files could be listed from this pack.").assertExists()
    }

    @Test fun theMetaLineIsTheTypeAndSize() {
        assertThat(fileMeta(PackFile("a.pdf", "a", "application/pdf", 1_500_000))).isEqualTo("PDF · 1.5 MB")
    }
}
