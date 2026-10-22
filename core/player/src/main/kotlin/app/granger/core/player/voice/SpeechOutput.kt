package app.granger.core.player.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.granger.core.speech.Pcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The voice's own output, beside ExoPlayer: one streaming track for speech. Writes are cooperative (they suspend
 * while the track's buffer is full and stop at cancellation), so Stop is immediate and nothing plays after it.
 * [volume] is the sleep timer's fade now and the mix's lever in V2.
 */
interface SpeechOutput : AutoCloseable {
    /** Readies the track for [sampleRate]; a different rate rebuilds it. Mono. */
    fun open(sampleRate: Int)
    suspend fun write(pcm: Pcm)
    fun play()
    fun pause()
    /** Drops everything written but not yet heard. */
    fun flush()
    var volume: Float
    /** Milliseconds of audio heard since the last [flush] or [open]. */
    val playedMs: Long
}

class AudioTrackSpeechOutput(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val bufferMs: Int = 1_000,
) : SpeechOutput {
    private var track: AudioTrack? = null
    private var rate = 0
    private var playing = false
    private var writtenFrames = 0L
    override var volume: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); track?.setVolume(field) }

    override fun open(sampleRate: Int) {
        if (track != null && rate == sampleRate) return
        val wasPlaying = playing
        release()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(minBytes, sampleRate * 2 * bufferMs / 1000)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(volume) }
        rate = sampleRate
        writtenFrames = 0
        if (wasPlaying) play()
    }

    override suspend fun write(pcm: Pcm) {
        val t = track ?: return
        val bytes = ByteBuffer.allocateDirect(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm.samples) bytes.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        bytes.flip()
        withContext(io) {
            while (bytes.hasRemaining()) {
                ensureActive()
                val n = t.write(bytes, bytes.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) throw IllegalStateException("AudioTrack write failed: $n")
                if (n == 0) delay(10)
            }
            writtenFrames += pcm.samples.size
        }
    }

    override fun play() { playing = true; track?.play() }
    override fun pause() { playing = false; track?.pause() }

    override fun flush() {
        val t = track ?: return
        t.pause()
        t.flush()
        writtenFrames = 0
        if (playing) t.play()
    }

    override val playedMs: Long
        get() {
            val t = track ?: return 0
            val frames = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            return if (rate > 0) frames * 1000 / rate else 0
        }

    private fun release() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        rate = 0
    }

    override fun close() { playing = false; release() }
}
