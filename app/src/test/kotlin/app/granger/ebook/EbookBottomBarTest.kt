package app.granger.ebook

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.granger.player.VoiceButton
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The e-book bar's voice button (Plan V2): headphones start, a tap pauses or resumes, a long press stops. */
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

    @Test fun idleStartsTheVoiceAndNeverStops() {
        bar(VoiceButton.IDLE)
        compose.onNodeWithContentDescription("Read aloud").performClick()
        compose.onNodeWithTag(EbookTags.VOICE).performTouchInput { longClick() }   // no long-press action: a long press is a tap
        assertThat(calls).doesNotContain("stop")
        assertThat(calls).doesNotContain("pauseResume")
        assertThat(calls.first()).isEqualTo("start")
    }

    @Test fun readingPausesOnATapAndStopsOnALongPress() {
        bar(VoiceButton.READING)
        compose.onNodeWithContentDescription("Pause reading").performClick()
        compose.onNodeWithTag(EbookTags.VOICE).performTouchInput { longClick() }
        assertThat(calls).containsExactly("pauseResume", "stop").inOrder()
    }

    @Test fun pausedResumesOnATap() {
        bar(VoiceButton.PAUSED)
        compose.onNodeWithContentDescription("Resume reading").performClick()
        assertThat(calls).containsExactly("pauseResume")
    }
}
