package app.granger.core.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.test.utils.FakeTimeline
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplayGainProcessorTest {
    private val processor = ReplayGainProcessor().apply {
        configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun process(vararg samples: Short): List<Short> {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val out = processor.output
        return List(out.remaining() / 2) { out.short }
    }

    /** The sink flushes the chain at each new stream with that stream's period; the gain follows its media item. */
    private fun startStream(mediaId: String) {
        val timeline = FakeTimeline(FakeTimeline.TimelineWindowDefinition.Builder()
            .setMediaItem(MediaItem.Builder().setMediaId(mediaId).build()).build())
        processor.flush(AudioProcessor.StreamMetadata.Builder()
            .setTimeline(timeline).setPeriodUid(timeline.getUidOfPeriod(0)).setPositionOffsetUs(0).build())
    }

    @Test fun unityWithoutGains() {
        assertThat(process(1_000, -2_000)).containsExactly(1_000.toShort(), (-2_000).toShort()).inOrder()
    }

    @Test fun gainFollowsTheStreamsMediaItemAndClips() {
        processor.setGains(mapOf("a" to 0.5f, "b" to 4f))
        startStream("a")
        assertThat(process(1_000, -2_000)).containsExactly(500.toShort(), (-1_000).toShort()).inOrder()
        startStream("b")
        assertThat(process(1_000, 20_000, -20_000)).containsExactly(4_000.toShort(), Short.MAX_VALUE, Short.MIN_VALUE).inOrder()
    }

    @Test fun transitionFallbackWhenTheSinkGivesNoStreamMetadata() {
        processor.setGains(mapOf("a" to 0.5f))
        processor.onMediaItemTransition("a")
        assertThat(process(1_000)).containsExactly(500.toShort())
    }
}
