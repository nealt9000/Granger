package app.granger.core.speech

import kotlinx.coroutines.flow.Flow

/** Which engine speaks and as whom. The string forms are what Settings stores. */
sealed interface VoiceId {
    /** One of the phone's text-to-speech voices: the engine's package and the voice's name. */
    data class System(val engine: String, val name: String) : VoiceId {
        override fun encode() = "system:$engine:$name"
    }

    /** A Granger voice: the directory under `voices/` it was unpacked into. */
    data class Granger(val dir: String) : VoiceId {
        override fun encode() = "granger:$dir"
    }

    fun encode(): String

    companion object {
        fun decode(s: String): VoiceId? {
            val parts = s.split(':', limit = 3)
            return when {
                parts.size == 3 && parts[0] == "system" && parts[1].isNotEmpty() && parts[2].isNotEmpty() -> System(parts[1], parts[2])
                parts.size == 2 && parts[0] == "granger" && parts[1].isNotEmpty() -> Granger(parts[1])
                else -> null
            }
        }
    }
}

/** A voice that can speak now, offline. */
data class Voice(val id: VoiceId, val title: String, val language: String, val speakers: Int = 1)

/**
 * An open speech engine for one voice. It produces audio and never plays it: playback, the mix, focus and the
 * saved place belong to the player, whichever engine is in use. Open one through a [SpeechFactory]; close it when the
 * voice has been quiet for a while (a Piper voice holds hundreds of megabytes while open).
 */
interface Speech : AutoCloseable {
    val voice: Voice

    /**
     * Mono float samples in [-1, 1], in chunks as the engine produces them, for [text] at [speed] (1.0 normal, above 1
     * faster). The contract is **one sentence per call**: engines return a sentence in one piece, so the first chunk is
     * the time to first audio. Each [Pcm] carries its sample rate. Cancelling the collector stops the engine early.
     */
    fun synthesize(text: String, speed: Float = 1f): Flow<Pcm>
}

interface SpeechFactory {
    /** Loads the engine for [voice]. Throws [SpeechException] when the voice is gone or the engine won't start. */
    suspend fun open(voice: Voice): Speech
}

class SpeechException(message: String, cause: Throwable? = null) : Exception(message, cause)
