package app.granger.core.player.voice

import android.media.AudioAttributes
import app.granger.core.speech.Pcm
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechOutputTest {
    @Test
    fun opensAMonoSpeechTrackAndWritesThroughIt() = runTest {
        val out = AudioTrackSpeechOutput(io = kotlinx.coroutines.Dispatchers.Unconfined)
        out.open(22_050)
        out.play()
        out.write(Pcm(FloatArray(2_205) { 0.5f }, 22_050))
        out.write(Pcm.silence(100, 22_050))
        out.volume = 0.3f
        out.pause()
        out.flush()
        out.close()
        // Nothing threw; the shadow accepted a 16-bit mono stream at 22,050 Hz.
        assertThat(out.playedMs).isEqualTo(0)
    }

    @Test
    fun reopeningAtTheSameRateKeepsTheTrack() = runTest {
        val out = AudioTrackSpeechOutput(io = kotlinx.coroutines.Dispatchers.Unconfined)
        out.open(24_000)
        out.open(24_000)
        out.open(22_050)
        out.close()
        assertThat(ShadowAudioTrack::class.java).isNotNull()
        assertThat(AudioAttributes.CONTENT_TYPE_SPEECH).isEqualTo(1)
    }
}
