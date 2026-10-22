package app.granger.player

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.Utterance
import app.granger.core.speech.UtteranceKind
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The voice's full-view sheet (Plan V2): the sentences, the controls, the music row only with music, Stop and Open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSheetTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val progress = VoiceProgress(
        ReadAddress.Text("k", 0, 1), Utterance(ReadAddress.Text("k", 0, 1), UtteranceKind.SENTENCE, "The one being read.", 0, 350L), 1,
        previous = "The one before.", next = "The one after.",
    )
    /** The first voice is the one [voiceUi] says is reading, so its chip is the selected, inert one. */
    private val voices = listOf(
        Voice((voiceUi().now as NowPlaying.Voice).voiceId, "Granger voice (ljspeech)", "en-US"),
        Voice(VoiceId.System("com.google.android.tts", "en-us-x-iob-local"), "System voice iob (en-US)", "en-US"),
    )

    private fun sheet(ui: PlayerUi) = compose.setContent {
        VoiceSheet(
            ui = ui, progress = progress, voices = voices, mixLevel = 0.3f,
            onPlayPause = { calls += "playPause" }, onPreviousParagraph = { calls += "prev" }, onNextParagraph = { calls += "next" },
            onBackSentence = { calls += "back" }, onSpeed = { calls += "speed:$it" }, onVoice = { calls += "voice:${it.encode()}" },
            onMix = { calls += "mix" }, onPlayPauseMusic = { calls += "musicPlayPause" }, onNextMusic = { calls += "musicNext" },
            onSleep = { calls += "sleep" }, onCancelSleep = { calls += "cancelSleep" },
            onStop = { calls += "stop" }, onOpen = { calls += "open" }, onDismiss = { calls += "dismiss" },
        )
    }

    @Test fun showsTheSentencesAndTheControlsReachTheirCallbacks() {
        sheet(voiceUi(section = "History"))
        compose.onNodeWithTag(VoiceSheetTags.PREVIOUS).assertTextEquals("The one before.")
        compose.onNodeWithTag(VoiceSheetTags.SENTENCE).assertTextEquals("The one being read.")
        compose.onNodeWithTag(VoiceSheetTags.NEXT).assertTextEquals("The one after.")
        compose.onNodeWithTag(VoiceSheetTags.PLAY_PAUSE).performScrollTo().performClick()
        compose.onNodeWithTag(VoiceSheetTags.STOP).performScrollTo().performClick()
        compose.onNodeWithTag(VoiceSheetTags.OPEN).performScrollTo().performClick()
        assertThat(calls).containsExactly("playPause", "stop", "open").inOrder()
    }

    @Test fun theMusicRowShowsOnlyWithMusicUnderTheVoice() {
        val alone = voiceUi()
        sheet(alone)
        compose.onNodeWithTag(VoiceSheetTags.MIX).assertDoesNotExist()
        compose.onNodeWithTag(VoiceSheetTags.MUSIC_PLAY_PAUSE).assertDoesNotExist()
    }

    @Test fun theMusicRowDrivesTheMusicNotTheVoice() {
        val music = musicUi().now as NowPlaying.Music
        val ui = voiceUi().let { it.copy(now = (it.now as NowPlaying.Voice).copy(music = music, musicPlaying = true)) }
        sheet(ui)
        compose.onNodeWithTag(VoiceSheetTags.MIX).assertExists()
        compose.onNodeWithTag(VoiceSheetTags.MUSIC_PLAY_PAUSE).performScrollTo().performClick()
        compose.onNodeWithTag(VoiceSheetTags.MUSIC_NEXT).performScrollTo().performClick()
        assertThat(calls).containsExactly("musicPlayPause", "musicNext").inOrder()
    }

    @Test fun pickingAnotherVoiceAsksForItAndTheChosenOneIsInert() {
        sheet(voiceUi())
        compose.onAllNodesWithTag(VoiceSheetTags.VOICE_CHIP)[0].performScrollTo().performClick()   // the one reading: inert
        compose.onAllNodesWithTag(VoiceSheetTags.VOICE_CHIP)[1].performScrollTo().performClick()
        assertThat(calls).containsExactly("voice:system:com.google.android.tts:en-us-x-iob-local")
    }
}
