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
 * names if it is still here, else the first Granger voice by [rank], else the first system voice.
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
            .sortedWith(compareBy<SherpaVoice> { rank(it.id) }.thenBy { it.id })
            .flatMap { voice ->
                val names = SherpaVoice.speakerNames(voice)
                if (names.isEmpty()) listOf(Voice(VoiceId.Granger(voice.id), SherpaSpeech.title(voice.id), "en-US"))
                else names.mapIndexed { i, name -> Voice(VoiceId.Granger(voice.id, i), SherpaSpeech.speakerTitle(name), "en-US", speakers = names.size) }
            }
        val remote = runCatching { systemVoices() }.getOrDefault(emptyList())
        return (local + remote).also { _voices.value = it }
    }

    /** The [wanted] voice; else, for a speaker the voice no longer has, the same voice's first speaker; else the first. */
    fun preferred(wanted: VoiceId?): Voice? = installed(wanted) ?: voices.value.firstOrNull()

    /** The [wanted] voice, or the same voice's first speaker for a speaker it no longer has; null when it isn't installed. */
    fun installed(wanted: VoiceId?): Voice? = voices.value.firstOrNull { it.id == wanted }
        ?: (wanted as? VoiceId.Granger)?.let { w -> voices.value.firstOrNull { (it.id as? VoiceId.Granger)?.dir == w.dir } }

    /**
     * The voice to go on reading a text with: the one the user [chosen] while it is installed, so a choice holds for
     * every read until another is made; else the voice the text was [saved] with. The choice itself is never changed
     * here, so it comes back once its voice is installed again.
     */
    suspend fun forResume(chosen: VoiceId?, saved: VoiceId?): VoiceId? {
        if (voices.value.isEmpty()) refresh()
        return installed(chosen)?.id ?: saved
    }

    fun factoryFor(id: VoiceId): SpeechFactory = when (id) {
        is VoiceId.Granger -> granger
        is VoiceId.System -> system
    }

    suspend fun open(voice: Voice): Speech = factoryFor(voice.id).open(voice)

    companion object {
        /**
         * The order Granger voices are offered in when nothing is chosen: the catalog's voices in catalog order, then
         * any other Piper voice, then the rest (a Kokoro build is too slow to read with on the phone: V0, RTF 1.5–2.2).
         */
        fun rank(id: String): Int = when {
            id in CATALOG_VOICES -> CATALOG_VOICES.indexOf(id)
            id.startsWith("vits-piper-") -> CATALOG_VOICES.size
            else -> CATALOG_VOICES.size + 1
        }

        /** The catalog's voices, best first: Maggie & Martin (V3, Neal's pick) lead, so with no choice made it reads (U3a). */
        val CATALOG_VOICES = listOf("vits-piper-en_US-granger_libritts-medium", "vits-piper-en_US-ljspeech-medium")
    }
}
