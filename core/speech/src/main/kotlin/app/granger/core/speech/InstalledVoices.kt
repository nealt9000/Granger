package app.granger.core.speech

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * What can speak now, offline: the Granger voices unpacked under each volume's `voices/`, then the system engine's
 * offline English voices. [refresh] after a voice is downloaded or removed. The preferred voice is the one Settings
 * names if it is still here, else the first Granger voice, else the first system voice.
 */
class InstalledVoices(
    private val voicesDirs: () -> List<File>,
    private val systemVoices: suspend () -> List<Voice>,
    private val granger: SpeechFactory,
    private val system: SpeechFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    val voices: StateFlow<List<Voice>> = _voices.asStateFlow()

    suspend fun refresh(): List<Voice> {
        val local = withContext(io) { voicesDirs().flatMap { SherpaVoice.list(it) } }
            .distinctBy { it.id }
            .map { Voice(VoiceId.Granger(it.id), SherpaSpeech.title(it.id), "en-US") }
        val remote = runCatching { systemVoices() }.getOrDefault(emptyList())
        return (local + remote).also { _voices.value = it }
    }

    fun preferred(wanted: VoiceId?): Voice? = voices.value.firstOrNull { it.id == wanted } ?: voices.value.firstOrNull()

    fun factoryFor(id: VoiceId): SpeechFactory = when (id) {
        is VoiceId.Granger -> granger
        is VoiceId.System -> system
    }

    suspend fun open(voice: Voice): Speech = factoryFor(voice.id).open(voice)
}
