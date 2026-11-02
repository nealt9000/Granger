package app.granger.voiceinput

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.granger.core.speech.Pcm
import app.granger.core.speech.Wav
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject

/** One phrase from `tools/asr/phrases.json`. */
data class Phrase(val id: String, val kind: String, val text: String)

/**
 * Debug-only (Plan A0): records the microphone at 16 kHz mono 16-bit, the format the replay feeds back, from the
 * `VOICE_RECOGNITION` source (tuned for recognizers: no noise suppression or automatic gain on most phones).
 */
class PhraseRecorder {
    @Volatile private var stopRequested = false

    /** Records until [stop] is called or the coroutine is cancelled; writes [file] and returns the length in ms. */
    @SuppressLint("MissingPermission") // The activity asks for RECORD_AUDIO before recording is offered.
    suspend fun record(file: File): Long = withContext(Dispatchers.IO) {
        stopRequested = false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        val samples = ArrayList<Short>(RATE * 10)
        val buffer = ShortArray(RATE / 50)
        try {
            record.startRecording()
            while (!stopRequested && coroutineContext.isActive) {
                val n = record.read(buffer, 0, buffer.size)
                for (i in 0 until n) samples += buffer[i]
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        val pcm = Pcm(FloatArray(samples.size) { samples[it] / 32768f }, RATE)
        Wav.write(pcm, file)
        samples.size * 1000L / RATE
    }

    fun stop() { stopRequested = true }

    companion object {
        const val RATE = 16_000

        fun load(file: File): List<Phrase> {
            val list = JSONObject(file.readText()).getJSONArray("phrases")
            return List(list.length()) { i -> list.getJSONObject(i).let { Phrase(it.getString("id"), it.getString("kind"), it.getString("text")) } }
        }
    }
}
