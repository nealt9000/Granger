package app.granger.core.player

import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import app.granger.core.player.voice.SpeechOutput
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Pcm
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechFactory
import app.granger.core.speech.TextReadSource
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The voice as the engine's third mode: the carrier item, focus, transport, the saved place and its restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerEngineVoiceTest {
    private val rig = PlayerTestRig()
    private val focus = FakeFocusRequester()

    @After fun tearDown() = rig.close()

    private class FakeSpeech(override val voice: Voice) : Speech {
        val spoken = mutableListOf<String>()
        var closed = 0
        override fun synthesize(text: String, speed: Float): Flow<Pcm> = flow {
            spoken += text
            emit(Pcm(FloatArray(2_205), 22_050))     // 100 ms per sentence
        }
        override fun close() { closed++ }
    }

    /** Each write (a sentence or a pause) waits for one [release], so the voice stays mid-read until the test lets it on. */
    private class FakeOutput : SpeechOutput {
        private val gate = Channel<Unit>(Channel.UNLIMITED)
        val events = mutableListOf<String>()
        var written = 0
        override var volume = 1f
        override val playedMs = 0L
        override fun open(sampleRate: Int) {}
        override suspend fun write(pcm: Pcm) { gate.receive(); written++ }
        override fun play() { events += "play" }
        override fun pause() { events += "pause" }
        override fun flush() { events += "flush" }
        override fun close() { events += "close" }
        fun release(n: Int) = repeat(n) { gate.trySend(Unit) }
    }

    private val grangerVoice = Voice(VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), "Granger voice (ljspeech)", "en-US")
    private val output = FakeOutput()
    private val speeches = mutableListOf<FakeSpeech>()
    private val factory = object : SpeechFactory {
        override suspend fun open(voice: Voice): Speech = FakeSpeech(voice).also { speeches += it }
    }
    private val voices = InstalledVoices({ emptyList() }, { listOf(grangerVoice) }, factory, factory)

    private val text = "One two. Three four five. Six.\n\nSeven eight. Nine.\n\nTen."     // six sentences, three paragraphs
    private val sources = mutableMapOf<String, ReadSource>()
    private fun source(key: String = "text:article", title: String = "Rosetta Stone", body: String = text): ReadSource =
        TextReadSource(key, title, body).also { sources[key] = it }

    private fun engine(): PlayerEngine = rig.engine(focus = focus, speechOutput = output, voices = voices, readSources = { key, _ -> sources[key] })

    private fun PlayerEngine.untilReady() { run(player).untilState(Player.STATE_READY) }

    /** Lets [writes] sentences-or-pauses through and runs the looper. */
    private fun speak(writes: Int) { output.release(writes); rig.idle() }

    @Test fun readAloudPausesMusicAndRequestsFocusAsSpeech() {
        val e = engine()
        val ids = rig.songs()
        e.playTracks(ids, 0, shuffle = false, source = PlaySource("songs", "Songs"))
        rig.idle(); e.untilReady()
        assertThat(e.ui.value.now).isInstanceOf(NowPlaying.Music::class.java)

        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(4)
        val now = e.ui.value.now
        assertThat(now).isInstanceOf(NowPlaying.Voice::class.java)
        now as NowPlaying.Voice
        assertThat(now.title).isEqualTo("Rosetta Stone")
        assertThat(now.voiceId).isEqualTo(grangerVoice.id)
        assertThat(now.ended).isFalse()
        assertThat(e.ui.value.playing).isTrue()
        assertThat(focus.held).isTrue()
        assertThat(e.player.currentMediaItem?.mediaMetadata?.title).isEqualTo("Reading · Rosetta Stone")
        assertThat(e.player.currentMediaItem?.mediaId).isEqualTo("voice:text:article")
        assertThat(e.queue.value).isEmpty()
        // The music queue was saved before the switch.
        assertThat(runBlocking { rig.playback.musicState() }).isNotNull()
        // The speech engine opened and is speaking in order.
        assertThat(speeches).hasSize(1)
        assertThat(speeches[0].spoken.take(2)).containsExactly("One two.", "Three four five.").inOrder()
        assertThat(output.events).contains("play")
        assertThat(e.chapterSteps).isTrue()
        assertThat(e.address()).startsWith("text:text:article#")
        assertThat(e.voiceProgress.value?.utterance?.text).isAnyOf("Three four five.", "Six.")
    }

    @Test fun theVoiceEndsPausedAndPlayReadsItAgainThenPausePauses() {
        val e = engine()
        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(12)
        assertThat(speeches[0].spoken).hasSize(6)
        assertThat((e.ui.value.now as NowPlaying.Voice).ended).isTrue()
        assertThat(e.ui.value.playing).isFalse()

        e.playPause()                                   // play after the end: from the start again
        rig.idle(); e.untilReady(); speak(2)
        assertThat(speeches).hasSize(1)                 // the same engine, not a second one
        assertThat(speeches[0].spoken[6]).isEqualTo("One two.")        // synthesis runs a few sentences ahead of the output
        assertThat(speeches[0].spoken.size).isIn(7..10)
        assertThat(e.ui.value.playing).isTrue()

        e.playPause()                                   // pause
        rig.idle()
        assertThat(e.ui.value.playing).isFalse()
        assertThat(output.events.takeLast(2)).containsExactly("pause", "flush").inOrder()
    }

    @Test fun nextAndPreviousStepParagraphsAndSkipsStepSentences() {
        val e = engine()
        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(2)
        e.next()                                        // → "Seven eight."
        rig.idle(); speak(0)
        assertThat(e.voiceProgress.value?.utterance?.text).isEqualTo("Seven eight.")
        e.skipForward()                                 // → "Nine."
        rig.idle()
        assertThat(e.voiceProgress.value?.utterance?.text).isEqualTo("Nine.")
        e.skipBack()                                    // → "Seven eight."
        rig.idle()
        assertThat(e.voiceProgress.value?.utterance?.text).isEqualTo("Seven eight.")
        e.previous()                                    // at the paragraph's first sentence → the previous paragraph
        rig.idle()
        assertThat(e.voiceProgress.value?.utterance?.text).isEqualTo("One two.")
    }

    @Test fun startingMusicStopsTheVoiceAndSavesItsPlace() {
        val e = engine()
        e.readAloud(source(), ReadAddress.Text("text:article", 1, 0), null)
        rig.idle(); e.untilReady(); speak(2)
        e.playTracks(rig.songs(), 0, shuffle = false, source = PlaySource("songs", "Songs"))
        rig.idle(); e.untilReady()
        assertThat(e.ui.value.now).isInstanceOf(NowPlaying.Music::class.java)
        val state = runBlocking { rig.playback.voiceState("text:article") }
        assertThat(state).isNotNull()
        assertThat(state!!.title).isEqualTo("Rosetta Stone")
        assertThat(ReadAddress.decode(state.address)).isEqualTo(ReadAddress.Text("text:article", 1, 1))   // "Nine." was being written
        assertThat(state.voiceId).isEqualTo(grangerVoice.id.encode())
        assertThat(rig.prefs.getString(PlayerSettingsKeys.LAST_MODE, null)).isEqualTo("music")
        assertThat(runBlocking { rig.playback.recentPlay("voice:text:article") }?.title).isEqualTo("Rosetta Stone")
    }

    @Test fun restoreLoadsTheVoicePausedAtItsSentenceWithoutOpeningTheEngine() {
        val first = engine()
        first.readAloud(source(), null, null)
        rig.idle(); first.untilReady(); speak(4)      // "Six." is being written
        first.release(save = true)
        assertThat(rig.prefs.getString(PlayerSettingsKeys.LAST_MODE, null)).isEqualTo("voice")
        val saved = runBlocking { rig.playback.voiceState("text:article") }!!
        assertThat(ReadAddress.decode(saved.address)).isEqualTo(ReadAddress.Text("text:article", 0, 2))
        val speechesBefore = speeches.size

        val second = engine()
        second.restore()
        rig.idle()
        val now = second.ui.value.now
        assertThat(now).isInstanceOf(NowPlaying.Voice::class.java)
        assertThat((now as NowPlaying.Voice).title).isEqualTo("Rosetta Stone")
        assertThat(second.ui.value.playing).isFalse()
        assertThat(speeches.size).isEqualTo(speechesBefore)          // no engine opened for a paused restore
        assertThat(second.address()).isEqualTo(saved.address)

        second.playPause()
        rig.idle(); speak(2)                            // the carrier may still be buffering; the voice speaks regardless
        assertThat(speeches.size).isEqualTo(speechesBefore + 1)
        assertThat(speeches.last().spoken.first()).isEqualTo("Six.")
        assertThat(second.ui.value.playing).isTrue()
    }

    @Test fun restoreWithAMissingSourceClearsTheStateOnce() {
        val first = engine()
        first.readAloud(source(), null, null)
        rig.idle(); first.untilReady(); speak(2)
        first.release(save = true)
        sources.clear()

        val second = engine()
        second.restore()
        rig.idle()
        assertThat(second.ui.value).isEqualTo(PlayerUi.IDLE)
        assertThat(runBlocking { rig.playback.voiceState("text:article") }).isNull()
        assertThat(rig.prefs.getString(PlayerSettingsKeys.LAST_VOICE, null)).isNull()
    }

    @Test fun transientFocusLossPausesTheVoiceAndGainResumesIt() {
        val e = engine()
        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(2)
        focus.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        rig.idle()
        assertThat(e.player.playWhenReady).isFalse()
        assertThat(e.ui.value.playing).isFalse()
        focus.change(AudioManager.AUDIOFOCUS_GAIN)
        rig.idle()
        assertThat(e.player.playWhenReady).isTrue()
        assertThat(e.ui.value.playing).isTrue()
    }

    @Test fun stopVoiceEmptiesThePlayerAndSaves() {
        val e = engine()
        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(2)
        e.stopVoice()
        rig.idle()
        assertThat(e.ui.value).isEqualTo(PlayerUi.IDLE)
        assertThat(e.player.mediaItemCount).isEqualTo(0)
        assertThat(runBlocking { rig.playback.voiceState("text:article") }).isNotNull()
        assertThat(e.voiceProgress.value).isNull()
    }

    @Test fun setSpeedIsClampedAndSaved() {
        val e = engine()
        e.readAloud(source(), null, null)
        rig.idle(); e.untilReady(); speak(2)
        e.setSpeed(5f)
        rig.idle()
        assertThat((e.ui.value.now as NowPlaying.Voice).speed).isEqualTo(2f)
        assertThat(runBlocking { rig.playback.voiceState("text:article") }?.speed).isEqualTo(2f)
    }

    @Test fun noVoiceInstalledMeansNothingHappens() {
        val none = InstalledVoices({ emptyList() }, { emptyList() }, factory, factory)
        val e = rig.engine(focus = focus, speechOutput = output, voices = none, readSources = { key, _ -> sources[key] })
        e.readAloud(source(), null, null)
        rig.idle()
        assertThat(e.ui.value).isEqualTo(PlayerUi.IDLE)
        assertThat(speeches).isEmpty()
    }
}
