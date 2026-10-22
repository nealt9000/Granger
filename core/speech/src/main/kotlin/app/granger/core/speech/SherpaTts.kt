package app.granger.core.speech

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A loaded sherpa-onnx voice. Loading happens in the constructor (the native engine reads the whole model), so
 * build one off the main thread. [synthesize] produces audio as the engine hands it over: with sherpa-onnx's
 * sentence-at-a-time generation that is one chunk per sentence of [text], so callers that want the first audio
 * early pass one sentence at a time. CPU only; the GPU stays LiteRT-LM's.
 */
class SherpaTts(val voice: SherpaVoice, val threads: Int = 2) : AutoCloseable {
    private val tts = OfflineTts(config = config(voice, threads))

    val sampleRate: Int get() = tts.sampleRate()
    val speakers: Int get() = tts.numSpeakers()

    /**
     * Mono float samples in [-1, 1] at [sampleRate], in chunks, for [text] spoken by [speaker] at [speed]
     * (1.0 normal; above 1 faster). Cancelling the collector stops the engine at its next chunk.
     */
    fun synthesize(text: String, speaker: Int = 0, speed: Float = 1f): Flow<FloatArray> = channelFlow {
        withContext(Dispatchers.Default) {
            // The callback runs on this thread, inside the native call; 1 keeps generating, 0 stops early.
            // sherpa's JNI finds it by reflection as `invoke([F)Ljava/lang/Integer;`, which an invokedynamic
            // lambda (Kotlin 2's default) doesn't have; an object expression compiles to a class that does.
            val callback = chunkCallback { samples ->
                if (!isActive) return@chunkCallback 0
                trySendBlocking(samples.copyOf())
                if (isActive) 1 else 0
            }
            tts.generateWithCallback(text, speaker, speed, callback)
        }
    }

    override fun close() = tts.free()

    internal companion object {
        /**
         * The callback sherpa-onnx's JNI can find: it looks up `invoke([F)Ljava/lang/Integer;` by reflection, which an
         * invokedynamic lambda (Kotlin 2's default) doesn't declare; an object expression compiles to a class that does.
         * Every callback handed to sherpa goes through here (`SherpaCallbackTest` checks the method is there).
         */
        fun chunkCallback(onChunk: (FloatArray) -> Int): (FloatArray) -> Int = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray): Int = onChunk(samples)
        }

        fun config(voice: SherpaVoice, threads: Int): OfflineTtsConfig {
            val model = when (voice) {
                is SherpaVoice.Piper -> OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.model.path,
                        tokens = voice.tokens.path,
                        dataDir = voice.espeakData.path,
                    ),
                )
                is SherpaVoice.Kokoro -> OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = voice.model.path,
                        voices = voice.voices.path,
                        tokens = voice.tokens.path,
                        dataDir = voice.espeakData.path,
                        lexicon = voice.lexicons.joinToString(",") { it.path },
                        // The multi-language builds need the language named; the English-only build takes none.
                        lang = if (voice.lexicons.isNotEmpty()) "en-us" else "",
                        dictDir = voice.dictDir?.path.orEmpty(),
                    ),
                )
            }
            model.numThreads = threads
            model.provider = "cpu"
            // One sentence per generation call: callers split text themselves and get a chunk per sentence.
            return OfflineTtsConfig(model = model, maxNumSentences = 1)
        }
    }
}
