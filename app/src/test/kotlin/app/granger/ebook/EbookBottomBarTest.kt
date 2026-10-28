package app.granger.ebook

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.granger.player.ListenTags
import app.granger.player.VoiceButton
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The e-book bar's Listen (Plan U2 Decision 6): headphones start; while this book is read, Pause or Play and a separate Stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EbookBottomBarTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun bar(voice: VoiceButton) = compose.setContent {
        EbookBottomBar(
            locator = null, positionsCount = 0, bookmarked = false,
            onSeek = {}, onContents = {}, onTextSettings = {}, onBookmark = {},
            onReadAloud = { calls += "start" }, voice = voice, onPauseResume = { calls += "pauseResume" }, onStop = { calls += "stop" },
        )
    }

    @Test fun idleShowsListenAndNoStop() {
        bar(VoiceButton.IDLE)
        compose.onNodeWithContentDescription("Listen").performClick()
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        assertThat(calls).containsExactly("start")
    }

    @Test fun readingPausesOnATapAndStopIsItsOwnButton() {
        bar(VoiceButton.READING)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertThat(calls).containsExactly("pauseResume", "stop").inOrder()
        assertThat(compose.onNodeWithContentDescription("Pause").getBoundsInRoot().left)
            .isLessThan(compose.onNodeWithContentDescription("Stop").getBoundsInRoot().left)
    }

    @Test fun aLongPressNoLongerStops() {
        bar(VoiceButton.READING)
        compose.onNodeWithTag(ListenTags.LISTEN).performTouchInput { longClick() }
        assertThat(calls).doesNotContain("stop")
    }

    @Test fun pausedPlaysOnATap() {
        bar(VoiceButton.PAUSED)
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Stop").assertExists()
        assertThat(calls).containsExactly("pauseResume")
    }
}
