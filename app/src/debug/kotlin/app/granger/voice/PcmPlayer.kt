package app.granger.voice

import app.granger.core.speech.Pcm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plays one passage's audio through an [AudioTrack] as speech, the way the voice will; one at a time. */
class PcmPlayer {
    private var track: AudioTrack? = null

    suspend fun play(pcm: Pcm) = withContext(Dispatchers.Default) {
        stop()
        val bytes = pcm.samples.size * 4
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(pcm.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm.samples, 0, pcm.samples.size, AudioTrack.WRITE_BLOCKING)
        track = t
        t.play()
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
