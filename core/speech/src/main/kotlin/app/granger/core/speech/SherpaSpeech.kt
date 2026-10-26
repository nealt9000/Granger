package app.granger.core.speech

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A Granger voice: a Piper model through sherpa-onnx, two threads (V0: four add nothing). The text is written out
 * for the phonemizer first ([SpokenText]: years, ranges, units) and respelled second ([Pronunciations]).
 */
class SherpaSpeech internal constructor(
    override val voice: Voice,
    private val tts: SherpaTts,
    private val pronunciations: Map<String, String> = Pronunciations.builtIn,
) : Speech {
    override fun synthesize(text: String, speed: Float): Flow<Pcm> =
        tts.synthesize(spoken(text), speaker = (voice.id as? VoiceId.Granger)?.speaker ?: 0, speed = speed).map { Pcm(it, tts.sampleRate) }

    /** What the engine is handed for [text]. */
    internal fun spoken(text: String): String = Pronunciations.apply(SpokenText.forPiper(text), pronunciations)

    override fun close() = tts.close()

    /** Opens Granger voices found under any of [voicesDirs] (one `voices/` folder per volume). */
    class Factory(private val voicesDirs: () -> List<File>, private val threads: Int = 2) : SpeechFactory {
        override suspend fun open(voice: Voice): Speech {
            val id = voice.id as? VoiceId.Granger ?: throw SpeechException("Not a Granger voice: ${voice.id.encode()}")
            val found = withContext(Dispatchers.IO) { voicesDirs().firstNotNullOfOrNull { SherpaVoice.detect(File(it, id.dir)) } }
                ?: throw SpeechException("The voice ${id.dir} is not installed")
            // Loading reads the whole model; off the main thread.
            val tts = withContext(Dispatchers.Default) {
                runCatching { SherpaTts(found, threads) }.getOrElse { throw SpeechException("The voice ${id.dir} didn't load", it) }
            }
            return SherpaSpeech(voice, tts)
        }
    }

    companion object {
        /** "vits-piper-en_US-ljspeech-medium" → "ljspeech"; anything unexpected stays whole. */
        fun shortName(dir: String): String {
            val parts = dir.split('-')
            val locale = parts.indexOfFirst { it.matches(Regex("[a-z]{2}_[A-Z]{2}")) }
            return if (locale >= 0 && locale + 1 < parts.size) parts[locale + 1] else dir
        }

        fun title(dir: String): String = "Granger voice (${shortName(dir)})"

        /** A named speaker's title: "Granger voice (Maggie)". */
        fun speakerTitle(name: String): String = "Granger voice ($name)"
    }
}
