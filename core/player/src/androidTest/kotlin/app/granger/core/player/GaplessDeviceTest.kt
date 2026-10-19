package app.granger.core.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gapless playback through the real decoder and the production audio chain (ReplayGain processor included), on the
 * device. The output is muted (player volume 0 is applied after the processors, so the tee still sees the audio).
 *
 * gapless-1.mp3 and gapless-2.mp3 are one continuous 5 s 440 Hz sine split sample-exactly at 2.5 s (110 250 samples
 * each, mono 44.1 kHz), each LAME-encoded with its encoder delay and padding in the Xing/LAME header.
 */
@RunWith(AndroidJUnit4::class)
class GaplessDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun item(name: String) = MediaItem.Builder().setMediaId(name).setUri("asset:///$name").build()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun lameHeaderGivesEncoderDelayAndPadding() {
        lateinit var player: ExoPlayer
        val ready = CountDownLatch(1)
        onMain {
            player = buildPlayer(context, ReplayGainProcessor())
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
            })
            player.setMediaItem(item("gapless-1.mp3"))
            player.prepare()
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
        var delay = 0
        var padding = 0
        onMain {
            val format = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO }.getTrackFormat(0)
            delay = format.encoderDelay
            padding = format.encoderPadding
            player.release()
        }
        assertThat(delay).isGreaterThan(0)
        assertThat(padding).isGreaterThan(0)
    }

    @Test fun transitionBetweenGaplessFilesHasNoSilence() {
        val capture = PcmCapture()
        val replayGain = ReplayGainProcessor()
        lateinit var player: ExoPlayer
        val ended = CountDownLatch(1)
        onMain {
            player = buildPlayer(context, replayGain, TeeAudioProcessor(capture))
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    replayGain.onMediaItemTransition(mediaItem?.mediaId)
                }
            })
            player.setMediaItems(listOf(item("gapless-1.mp3"), item("gapless-2.mp3")))
            player.prepare()
            player.play()
        }
        assertThat(ended.await(30, TimeUnit.SECONDS)).isTrue()
        onMain { player.release() }

        assertThat(capture.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(capture.channels).isEqualTo(1)
        val samples = capture.samples()
        // Both halves, trimmed of their delay and padding: within one MP3 frame of the 220 500 source samples.
        assertThat(samples.size).isIn(com.google.common.collect.Range.closed(220_500 - 1_152, 220_500 + 1_152))
        // No run of silence longer than 5 ms anywhere between the true start and end (the boundary is at ~2.5 s).
        val (longest, at) = longestQuietRun(samples, from = 2_205, to = samples.size - 2_205)
        val starts = quietRuns(samples)
        assertWithMessage("longest quiet run $longest ending at $at of ${samples.size}; runs>20: $starts")
            .that(longest).isAtMost(44_100 * 5 / 1_000)
        // And the sine runs on across the boundary without a jump: no step there is bigger than the sine's own steps.
        val steady = maxStep(samples, 20_000, 100_000)
        val boundary = maxStep(samples, 110_250 - 2_000, 110_250 + 2_000)
        assertWithMessage("max step at the boundary $boundary vs. steady $steady").that(boundary.toDouble()).isAtMost(steady * 1.2)
        // Each file reached the processor as its own stream.
        assertThat(replayGain.seenMediaIds()).containsAtLeast("gapless-1.mp3", "gapless-2.mp3")
    }

    /** "Quiet" is |s| ≤ 32 of 32 767: the sine's amplitude (~4 000) steps ~250 per sample, so only true gaps qualify. */
    private fun longestQuietRun(s: ShortArray, from: Int, to: Int): Pair<Int, Int> {
        var longest = 0
        var end = -1
        var run = 0
        for (i in from until to) {
            if (abs(s[i].toInt()) <= 32) { run++; if (run > longest) { longest = run; end = i } } else run = 0
        }
        return longest to end
    }

    private fun maxStep(s: ShortArray, from: Int, to: Int): Int =
        (from + 1 until to).maxOf { abs(s[it] - s[it - 1]) }

    /** Every quiet run over 20 samples, as start+length (diagnostics). */
    private fun quietRuns(s: ShortArray): List<String> {
        val out = mutableListOf<String>()
        var run = 0
        for (i in s.indices) {
            if (abs(s[i].toInt()) <= 32) run++ else { if (run > 20) out += "${i - run}+$run"; run = 0 }
        }
        if (run > 20) out += "${s.size - run}+$run"
        return out
    }

    private class PcmCapture : TeeAudioProcessor.AudioBufferSink {
        @Volatile var encoding = C.ENCODING_INVALID
        @Volatile var channels = 0
        private val out = java.io.ByteArrayOutputStream()

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            this.encoding = encoding
            channels = channelCount
        }

        @Synchronized override fun handleBuffer(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            out.write(bytes)
        }

        @Synchronized fun samples(): ShortArray {
            val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.nativeOrder()).asShortBuffer()
            return ShortArray(b.remaining()).also { b.get(it) }
        }
    }
}
