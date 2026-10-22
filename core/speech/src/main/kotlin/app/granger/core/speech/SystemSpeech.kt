package app.granger.core.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A system voice as the engine reports it. [offline] is `!isNetworkConnectionRequired`. */
data class TtsVoice(val name: String, val languageTag: String, val offline: Boolean, val quality: Int)

/** What the voice needs from the phone's text-to-speech; [AndroidTtsEngine] is the real one, tests use a fake. */
interface TtsEngine {
    val engineName: String
    fun voices(): List<TtsVoice>
    fun setVoice(name: String): Boolean
    fun setSpeechRate(rate: Float)
    /** Starts synthesis of [text] into [file]; [onDone] or [onError] follows on the engine's thread. False when refused. */
    fun synthesizeToFile(text: String, file: File, utteranceId: String, onDone: () -> Unit, onError: (Int) -> Unit): Boolean
    fun stop()
    fun shutdown()
}

/** The phone's own engine through [TextToSpeech]. Create it once per process with [create]; it takes a moment to bind. */
class AndroidTtsEngine private constructor(private val tts: TextToSpeech) : TtsEngine {
    private val pending = ConcurrentHashMap<String, Pair<() -> Unit, (Int) -> Unit>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.first?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, -1)
            override fun onError(utteranceId: String?, errorCode: Int) { pending.remove(utteranceId)?.second?.invoke(errorCode) }
        })
    }

    override val engineName: String get() = runCatching { tts.defaultEngine }.getOrNull() ?: "system"

    override fun voices(): List<TtsVoice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet()).map {
        TtsVoice(it.name, it.locale.toLanguageTag(), !it.isNetworkConnectionRequired, it.quality)
    }

    override fun setVoice(name: String): Boolean {
        val voice = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet()).firstOrNull { it.name == name } ?: return false
        return tts.setVoice(voice) == TextToSpeech.SUCCESS
    }

    override fun setSpeechRate(rate: Float) { tts.setSpeechRate(rate) }

    override fun synthesizeToFile(text: String, file: File, utteranceId: String, onDone: () -> Unit, onError: (Int) -> Unit): Boolean {
        pending[utteranceId] = onDone to onError
        val queued = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (queued != TextToSpeech.SUCCESS) pending.remove(utteranceId)
        return queued == TextToSpeech.SUCCESS
    }

    override fun stop() { tts.stop() }
    override fun shutdown() { tts.shutdown() }

    companion object {
        suspend fun create(context: Context): AndroidTtsEngine = suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context.applicationContext) { status ->
                val t = created
                if (status == TextToSpeech.SUCCESS && t != null) cont.resume(AndroidTtsEngine(t))
                else cont.resumeWithException(SpeechException("The phone's text-to-speech didn't start (status $status)"))
            }
        }
    }
}

/**
 * The system engine behind [Speech]: each sentence goes through `synthesizeToFile` into [scratch] and is read back
 * as PCM, so the player owns playback (the engine's own `speak()` would play it itself and bring its own session).
 */
class SystemSpeech internal constructor(private val engine: TtsEngine, override val voice: Voice, private val scratch: File) : Speech {
    override fun synthesize(text: String, speed: Float): Flow<Pcm> = flow {
        scratch.mkdirs()
        val file = File(scratch, "s-${System.nanoTime()}.wav")
        try {
            engine.setSpeechRate(speed)
            suspendCancellableCoroutine<Unit> { cont ->
                val queued = engine.synthesizeToFile(
                    text, file, file.name,
                    onDone = { if (cont.isActive) cont.resume(Unit) },
                    onError = { code -> if (cont.isActive) cont.resumeWithException(SpeechException("Text-to-speech error $code")) },
                )
                if (!queued && cont.isActive) cont.resumeWithException(SpeechException("Text-to-speech refused the sentence"))
                cont.invokeOnCancellation { engine.stop() }
            }
            emit(withContext(Dispatchers.IO) { Wav.read(file) })
        } finally {
            file.delete()
        }
    }

    /** The shared engine stays up for the next voice; [Factory.shutdown] ends it. */
    override fun close() {}

    /** Opens system voices over one shared engine, created on first use. */
    class Factory(private val create: suspend () -> TtsEngine, private val scratch: File) : SpeechFactory {
        constructor(context: Context) : this({ AndroidTtsEngine.create(context) }, File(context.cacheDir, "speech"))

        private val lock = Mutex()
        private var engine: TtsEngine? = null

        suspend fun engine(): TtsEngine = lock.withLock { engine ?: create().also { engine = it } }

        /** The engine's offline English voices, as [Voice]s; empty when the engine won't start. */
        suspend fun voices(): List<Voice> {
            val e = runCatching { engine() }.getOrNull() ?: return emptyList()
            return e.voices()
                .filter { it.offline && Locale.forLanguageTag(it.languageTag).language == Locale.ENGLISH.language }
                .sortedWith(compareByDescending<TtsVoice> { it.quality }.thenBy { it.name })
                .map { Voice(VoiceId.System(e.engineName, it.name), "${it.name} (${it.languageTag})", it.languageTag) }
        }

        override suspend fun open(voice: Voice): Speech {
            val id = voice.id as? VoiceId.System ?: throw SpeechException("Not a system voice: ${voice.id.encode()}")
            val e = engine()
            if (!e.setVoice(id.name)) throw SpeechException("The system voice ${id.name} is no longer installed")
            return SystemSpeech(e, voice, scratch)
        }

        suspend fun shutdown() = lock.withLock { engine?.shutdown(); engine = null }
    }
}
