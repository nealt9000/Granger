package app.granger.player

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedVoice
import app.granger.core.player.VoiceChapters
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadChapter
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

/** The player's Reader tab (Plan U2 Decision 5): the sentences, the five controls, the music row only with music, Stop, Open and Resume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class ReaderTabTest {
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
    private val last = SavedVoice("book:pan", "Peter Pan", "Chapter 3", ReadAddress.Text("book:pan", 4, 0), 1f, null)

    private val controls = ReaderControls(
        onPlayPause = { calls += "playPause" }, onPreviousParagraph = { calls += "prevParagraph" }, onBackSentence = { calls += "back" },
        onNextSentence = { calls += "nextSentence" }, onNextParagraph = { calls += "nextParagraph" },
        onSpeed = { calls += "speed:$it" }, onVoice = { calls += "voice:${it.encode()}" },
        onMix = { calls += "mix" }, onPlayPauseMusic = { calls += "musicPlayPause" }, onNextMusic = { calls += "musicNext" },
        onSleep = { calls += "sleep" }, onCancelSleep = { calls += "cancelSleep" },
        onStop = { calls += "stop" }, onOpen = { calls += "open:${it.title}" }, onResume = { calls += "resume:${it.sourceKey}" },
    )

    private fun reader(ui: PlayerUi, saved: SavedModes = SavedModes(null, null, null), with: ReaderControls = controls) = compose.setContent {
        Reader(ui, progress, saved, voices, mixLevel = 0.3f, with)
    }

    @Test fun showsTheSentencesAndTheControlsReachTheirCallbacks() {
        reader(voiceUi(section = "History"))
        compose.onNodeWithText("Rosetta Stone · History").assertExists()
        compose.onNodeWithTag(ReaderTags.PREVIOUS).assertTextEquals("The one before.")
        compose.onNodeWithTag(ReaderTags.SENTENCE).assertTextEquals("The one being read.")
        compose.onNodeWithTag(ReaderTags.NEXT).assertTextEquals("The one after.")
        compose.onNodeWithContentDescription("Previous paragraph").performClick()
        compose.onNodeWithContentDescription("Back a sentence").performClick()
        compose.onNodeWithTag(ReaderTags.PLAY_PAUSE).performClick()
        compose.onNodeWithContentDescription("Next sentence").performClick()
        compose.onNodeWithContentDescription("Next paragraph").performClick()
        compose.onNodeWithTag(ReaderTags.STOP).performScrollTo().performClick()
        compose.onNodeWithTag(ReaderTags.OPEN).performScrollTo().performClick()
        assertThat(calls).containsExactly(
            "prevParagraph", "back", "playPause", "nextSentence", "nextParagraph", "stop", "open:Rosetta Stone",
        ).inOrder()
    }

    @Test fun theMusicRowShowsOnlyWithMusicUnderTheVoice() {
        reader(voiceUi())
        compose.onNodeWithTag(ReaderTags.MIX).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTags.MUSIC_PLAY_PAUSE).assertDoesNotExist()
        compose.onNodeWithTag(ReaderTags.SLEEP).performScrollTo().assertExists()
    }

    @Test fun theMusicRowDrivesTheMusicNotTheVoice() {
        val music = musicUi().now as NowPlaying.Music
        val ui = voiceUi().let { it.copy(now = (it.now as NowPlaying.Voice).copy(music = music, musicPlaying = true)) }
        reader(ui)
        compose.onNodeWithTag(ReaderTags.MIX).assertExists()
        compose.onNodeWithTag(ReaderTags.MUSIC_PLAY_PAUSE).performScrollTo().performClick()
        compose.onNodeWithTag(ReaderTags.MUSIC_NEXT).performScrollTo().performClick()
        assertThat(calls).containsExactly("musicPlayPause", "musicNext").inOrder()
    }

    @Test fun pickingAnotherVoiceAsksForItAndTheChosenOneIsInert() {
        reader(voiceUi())
        compose.onAllNodesWithTag(ReaderTags.VOICE_CHIP)[0].performScrollTo().performClick()   // the one reading: inert
        compose.onAllNodesWithTag(ReaderTags.VOICE_CHIP)[1].performScrollTo().performClick()
        assertThat(calls).containsExactly("voice:system:com.google.android.tts:en-us-x-iob-local")
    }

    @Test fun chaptersListTheTextsChaptersWithTheCurrentOneAndAPickMovesTheVoice() {
        val book = VoiceChapters(listOf(ReadChapter("Act I"), ReadChapter("Scene one", depth = 1), ReadChapter("Act II")), current = 1)
        reader(voiceUi(), with = controls.copy(chapters = { book }, onChapter = { calls += "chapter:$it" }))
        compose.onNodeWithTag(ReaderTags.CHAPTERS).performScrollTo().assertExists()
        compose.onNodeWithText("Scene one").assertExists()                      // the row names the current chapter
        compose.onNodeWithTag(ReaderTags.CHAPTERS).performClick()
        compose.onNodeWithTag(ReaderTags.chapter(2)).performClick()
        assertThat(calls).containsExactly("chapter:2")
        compose.onNodeWithTag(ReaderTags.chapter(2)).assertDoesNotExist()      // the sheet closed
    }

    @Test fun aTextWithoutChaptersHasNoChaptersRow() {
        reader(voiceUi(), with = controls.copy(chapters = { VoiceChapters(emptyList(), null) }))
        compose.onNodeWithTag(ReaderTags.SLEEP).performScrollTo().assertExists()
        compose.onNodeWithTag(ReaderTags.CHAPTERS).assertDoesNotExist()
    }

    @Test fun nothingLoadedOffersToResumeTheLastRead() {
        reader(PlayerUi.IDLE, SavedModes(null, null, null, voice = last, lastWasVoice = true))
        compose.onNodeWithTag(ReaderTags.SENTENCE).assertDoesNotExist()
        compose.onNodeWithText("Open a book or article and tap Listen").assertExists()
        compose.onNodeWithTag(ReaderTags.RESUME).assertTextEquals("Resume Peter Pan · Chapter 3").performClick()
        assertThat(calls).containsExactly("resume:book:pan")
    }

    @Test fun musicPlayingWithoutTheVoiceStillOffersResume() {
        reader(musicUi(), SavedModes(null, null, null, voice = last.copy(section = null)))
        compose.onNodeWithTag(ReaderTags.RESUME).assertTextEquals("Resume Peter Pan")
    }

    @Test fun nothingEverReadOnlySaysHowToStart() {
        reader(PlayerUi.IDLE)
        compose.onNodeWithTag(ReaderTags.NOTHING).assertExists()
        compose.onNodeWithTag(ReaderTags.RESUME).assertDoesNotExist()
        compose.onNodeWithText("Open a book or article and tap Listen").assertExists()
    }

    @Test fun beforeTheDbIsReadNothingIsSaid() {
        reader(PlayerUi.IDLE, SavedModes.UNKNOWN)
        compose.onNodeWithTag(ReaderTags.NOTHING).assertDoesNotExist()
    }

    @Test fun theViewModelsControlsReachTheHub() {
        val hub = FakePlayerHub(voiceUi())
        val vm = PlayerViewModel(hub)
        val wired = vm.readerControls(onOpen = {}, onResume = {})
        wired.onNextSentence(); wired.onBackSentence(); wired.onNextParagraph(); wired.onPreviousParagraph(); wired.onChapter(3); wired.onStop()
        assertThat(hub.calls).containsExactly(
            "nextSentence", "previousSentence", "nextParagraph", "previousParagraph", "jumpToChapter:3", "stopVoice",
        ).inOrder()
        hub.chapters = VoiceChapters(listOf(ReadChapter("One")), 0)
        assertThat(kotlinx.coroutines.runBlocking { wired.chapters() }).isEqualTo(hub.chapters)
    }
}
