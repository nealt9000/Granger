package app.granger.voice

import app.granger.core.speech.Pcm
import app.granger.core.speech.Wav

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.granger.core.speech.SherpaTts
import app.granger.core.speech.SherpaVoice
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** An engine as the spike drives it: load, then one sentence at a time. */
interface SpikeEngine : AutoCloseable {
    val name: String

    /** Loads the engine; what it found, for the log (voices, settings), or null. */
    suspend fun load(): String?

    /** Synthesizes one [sentence]. [onFirstAudio] is called when the engine's first audio arrives, if it can tell. */
    suspend fun synthesize(sentence: String, onFirstAudio: () -> Unit): Pcm
}

/** sherpa-onnx with a voice directory (Piper or Kokoro), at [threads], speaking as [speaker]. */
class SherpaSpikeEngine(private val voice: SherpaVoice, private val threads: Int, private val speaker: Int = 0) : SpikeEngine {
    override val name: String = voice.id + if (speaker != 0) "#$speaker" else ""
    private var tts: SherpaTts? = null

    override suspend fun load(): String? {
        val t = withContext(Dispatchers.Default) { SherpaTts(voice, threads) }
        tts = t
        return "sampleRate=${t.sampleRate} speakers=${t.speakers} model=${voice.model.name}"
    }

    override suspend fun synthesize(sentence: String, onFirstAudio: () -> Unit): Pcm {
        val t = checkNotNull(tts) { "load() first" }
        val chunks = mutableListOf<FloatArray>()
        var first = true
        t.synthesize(sentence, speaker).collect { chunk ->
            if (first) { first = false; onFirstAudio() }
            chunks += chunk
        }
        return Pcm(Wav.concat(chunks), t.sampleRate)
    }

    override fun close() {
        tts?.close()
        tts = null
    }
}

/**
 * The phone's own engine through [TextToSpeech], synthesizing each sentence to a WAV file (the app, not the engine,
 * will own playback). Picks an offline English voice. First audio is the engine's `onAudioAvailable`, which not every
 * engine sends; then the row shows no first-audio time.
 */
class SystemSpikeEngine(private val context: Context, private val scratch: File) : SpikeEngine {
    override val name: String = "system"
    private var tts: TextToSpeech? = null

    override suspend fun load(): String? {
        val t = suspendCancellableCoroutine<TextToSpeech> { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                val c = created
                if (status == TextToSpeech.SUCCESS && c != null) cont.resume(c)
                else cont.resumeWithException(IllegalStateException("TextToSpeech init failed: $status"))
            }
        }
        tts = t
        val voices = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
        val offline = voices.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.ENGLISH.language }
        val chosen = offline.firstOrNull { it == t.defaultVoice } ?: offline.maxByOrNull { it.quality }
        if (chosen != null) t.setVoice(chosen)
        val engine = runCatching { t.defaultEngine }.getOrNull()
        return "engine=$engine chosen=${chosen?.describe() ?: "none"} offlineEnglishVoices=${offline.size} allVoices=${voices.size}" +
            offline.joinToString(prefix = " [", postfix = "]") { it.describe() }
    }

    private fun Voice.describe() = "$name(${locale.toLanguageTag()},q=$quality,lat=$latency)"

    override suspend fun synthesize(sentence: String, onFirstAudio: () -> Unit): Pcm {
        val t = checkNotNull(tts) { "load() first" }
        val file = File(scratch, "system-${System.nanoTime()}.wav")
        val id = file.name
        suspendCancellableCoroutine<Unit> { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                private var first = true
                override fun onStart(utteranceId: String?) {}
                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                    if (utteranceId == id && first) { first = false; onFirstAudio() }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onError(utteranceId, -1)
                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("TextToSpeech error $errorCode"))
                }
            })
            val queued = t.synthesizeToFile(sentence, Bundle(), file, id)
            if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resumeWithException(IllegalStateException("synthesizeToFile refused: $queued"))
            cont.invokeOnCancellation { t.stop() }
        }
        return try {
            withContext(Dispatchers.IO) { Wav.read(file) }
        } finally {
            file.delete()
        }
    }

    override fun close() {
        tts?.shutdown()
        tts = null
    }
}
