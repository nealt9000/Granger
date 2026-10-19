package app.granger.core.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.util.Collections

/**
 * Applies the ReplayGain factor of whichever media item the audio being processed belongs to.
 *
 * Gains are set per media id ([setGains]) from [ReplayGainMath.factor]; a missing id is unity. The sink flushes its
 * processors with the new stream's period at each stream change, after draining the old one, so the gain switches
 * at the exact sample where the next file starts, with nothing dropped or inserted (gapless). If the sink ever gives
 * no stream metadata, [onMediaItemTransition] (from the player's transition callback) is the fallback; it is ignored
 * once metadata has been seen, because the callback comes later than the audio it describes.
 *
 * Always active (unity is a copy), so a gain change never needs a reconfiguration. Handles 16-bit and float PCM.
 */
class ReplayGainProcessor : BaseAudioProcessor() {
    @Volatile private var gains: Map<String, Float> = emptyMap()
    @Volatile private var currentId: String? = null
    @Volatile private var sawStreamMetadata = false
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    fun setGains(byMediaId: Map<String, Float>) { gains = byMediaId }

    /** The factor set for [mediaId] (tests). */
    internal fun gainOf(mediaId: String): Float = gains[mediaId] ?: 1f

    fun onMediaItemTransition(mediaId: String?) { if (!sawStreamMetadata) currentId = mediaId }

    /** The media ids this processor has switched to, in order (diagnostics and the device test). */
    fun seenMediaIds(): List<String> = synchronized(seen) { seen.toList() }

    private val gain: Float get() = currentId?.let { gains[it] } ?: 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val timeline = streamMetadata.timeline
        val uid = streamMetadata.periodUid ?: return
        if (timeline.isEmpty) return
        val index = timeline.getIndexOfPeriod(uid)
        if (index == C.INDEX_UNSET) return
        val windowIndex = timeline.getPeriod(index, period).windowIndex
        val id = timeline.getWindow(windowIndex, window).mediaItem.mediaId
        sawStreamMetadata = true
        currentId = id
        seen += id
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val g = gain
        if (g == 1f) {
            out.put(inputBuffer)
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) out.putFloat(inputBuffer.float * g)
        } else {
            while (inputBuffer.remaining() >= 2) {
                val v = (inputBuffer.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.putShort(v.toShort())
            }
        }
        out.flip()
    }
}

/**
 * Default renderers with [processors] (ReplayGain first) in front of the sink's own speed and silence processing, and
 * the MP3 gapless fix in [GaplessMp3AudioRenderer].
 */
class GrangerRenderersFactory(context: Context, private val processors: Array<AudioProcessor>) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(processors)
            .build()

    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        audioSink: AudioSink, eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>,
    ) {
        // Only the MediaCodec renderer: Granger ships no decoder extensions.
        out += GaplessMp3AudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink)
    }
}

/**
 * Android's software MP3 decoder (`c2.android.mp3.decoder`) already drops the 529-sample MPEG decoder delay, which
 * Media3 also trims (it adds those 529 samples to the LAME encoder delay). Every MP3 then comes out 529 samples
 * (12 ms) early and ends in 12 ms of its own padding: a gap between gapless tracks. For that decoder the delay is
 * moved to the padding, which restores the exact samples. GaplessDeviceTest pins this on a real device.
 */
private class GaplessMp3AudioRenderer(
    context: Context, codecAdapterFactory: MediaCodecAdapter.Factory, selector: MediaCodecSelector, enableDecoderFallback: Boolean,
    handler: Handler, listener: AudioRendererEventListener, sink: AudioSink,
) : MediaCodecAudioRenderer(context, codecAdapterFactory, selector, enableDecoderFallback, handler, listener, sink) {
    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val selfTrims = format.sampleMimeType == MimeTypes.AUDIO_MPEG && codecInfo?.name in SELF_TRIMMING_MP3_DECODERS
        val fixed = if (selfTrims && format.encoderDelay >= MPEG_DECODER_DELAY) {
            format.buildUpon()
                .setEncoderDelay(format.encoderDelay - MPEG_DECODER_DELAY)
                .setEncoderPadding(format.encoderPadding + MPEG_DECODER_DELAY)
                .build()
        } else {
            format
        }
        super.onOutputFormatChanged(fixed, mediaFormat)
    }

    private companion object {
        const val MPEG_DECODER_DELAY = 529
        val SELF_TRIMMING_MP3_DECODERS = setOf("c2.android.mp3.decoder")
    }
}

/**
 * The one production ExoPlayer: focus handled by [FocusPolicy] (so `handleAudioFocus = false`), pause when headphones
 * are unplugged, a wake lock for local playback, and [replayGain] (plus [extra] processors after it) in the sink.
 */
fun buildPlayer(context: Context, replayGain: ReplayGainProcessor, vararg extra: AudioProcessor): ExoPlayer =
    ExoPlayer.Builder(context, GrangerRenderersFactory(context, arrayOf(replayGain, *extra)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
