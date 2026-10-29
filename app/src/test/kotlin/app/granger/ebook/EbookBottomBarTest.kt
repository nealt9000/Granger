package app.granger.ebook

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.granger.player.ListenTags
import app.granger.player.VoiceButton
import app.granger.reader.ReaderBarTags
import app.granger.reader.ReaderBottomBar
import app.granger.reader.ReaderTopBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The e-book's and PDF's bars, the wiki's shared ones (Plan U3c Decision 4): ←, the title and Listen above; the place
 * and slider, then Contents, Search in book, Aa and the bookmark below. A PDF has no Search in book and no Listen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EbookBottomBarTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val locator = Locator(
        href = Url("chapter1.xhtml")!!, mediaType = MediaType.XHTML, title = "Chapter 1",
        locations = Locator.Locations(totalProgression = 0.25),
    )

    private fun bars(voice: VoiceButton = VoiceButton.IDLE, isPdf: Boolean = false) = compose.setContent {
        val actions = bookBarActions(
            isPdf = isPdf, bookmarked = false, onBack = { calls += "back" },
            onContents = { calls += "contents" }, onSearch = { calls += "search" }, onTextSettings = { calls += "aa" },
            onBookmark = { calls += "star" },
            onListen = { calls += "start" }, voice = voice, onPauseResume = { calls += "pauseResume" }, onStop = { calls += "stop" },
        )
        Column {
            ReaderTopBar("Pride and Prejudice", actions)
            ReaderBottomBar(actions) { BookProgress(locator, 0) { calls += "seek" } }
        }
    }

    @Test fun listenIsInTheTopBarBesideTheTitle() {
        bars()
        val top = compose.onNodeWithTag(ReaderBarTags.TOP).getBoundsInRoot()
        val listen = compose.onNodeWithContentDescription("Listen").getBoundsInRoot()
        assertThat(listen.bottom).isAtMost(top.bottom)
        assertThat(compose.onNodeWithText("Pride and Prejudice").getBoundsInRoot().left).isLessThan(listen.left)
        // A book has no ⋯ of its own.
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
    }

    @Test fun theBottomBarHasThePlaceThenContentsSearchAaBookmark() {
        bars()
        compose.onNodeWithText("Chapter 1", substring = true).assertExists()
        val names = listOf("Contents", "Search in book", "Text settings", "Add bookmark")
        val lefts = names.map { compose.onNodeWithContentDescription(it).getBoundsInRoot().left }
        assertThat(lefts).isInStrictOrder()
        for (n in names) compose.onNodeWithContentDescription(n).performClick()
        assertThat(calls).containsExactly("contents", "search", "aa", "star").inOrder()
        // Contents appears once, in the bottom bar.
        assertThat(compose.onNodeWithContentDescription("Contents").getBoundsInRoot().top)
            .isAtLeast(compose.onNodeWithTag(ReaderBarTags.TOP).getBoundsInRoot().bottom)
    }

    @Test fun aPdfHasNoSearchInBookAndNoListen() {
        bars(isPdf = true)
        compose.onNodeWithContentDescription("Search in book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
        for (n in listOf("Contents", "Text settings", "Add bookmark")) compose.onNodeWithContentDescription(n).assertExists()
    }

    @Test fun idleShowsListenAndNoStop() {
        bars(VoiceButton.IDLE)
        compose.onNodeWithContentDescription("Listen").performClick()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertThat(calls).containsExactly("start")
    }

    @Test fun readingPausesOnATapAndStopIsItsOwnButton() {
        bars(VoiceButton.READING)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertThat(calls).containsExactly("pauseResume", "stop").inOrder()
        assertThat(compose.onNodeWithContentDescription("Pause").getBoundsInRoot().left)
            .isLessThan(compose.onNodeWithContentDescription("Stop").getBoundsInRoot().left)
    }

    @Test fun aLongPressNoLongerStops() {
        bars(VoiceButton.READING)
        compose.onNodeWithTag(ListenTags.LISTEN).performTouchInput { longClick() }
        assertThat(calls).doesNotContain("stop")
    }

    @Test fun pausedPlaysOnATap() {
        bars(VoiceButton.PAUSED)
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Stop").assertExists()
        assertThat(calls).containsExactly("pauseResume")
    }
}
