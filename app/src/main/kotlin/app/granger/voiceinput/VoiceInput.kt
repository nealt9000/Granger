package app.granger.voiceinput

import app.granger.core.speech.Availability
import app.granger.core.speech.DownloadOutcome
import app.granger.core.speech.RecognizerTags
import app.granger.core.speech.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which recognizer language the mic would listen in now ([tag], null for none) and whether it's installed. */
data class VoiceInputStatus(
    val tag: String? = null,
    val availability: Availability = Availability.UNAVAILABLE,
    /** The phone has an on-device recognizer at all (Settings → Voice input shows only then). */
    val phoneCanListen: Boolean = false,
) {
    /** The mic is shown only when the language is installed (Neal: hidden on phones that can't). */
    val micShown: Boolean get() = tag != null && availability == Availability.READY
}

/**
 * Voice input for the whole app (Plan A1): the phone's recognizer, the language it listens in (the app's language;
 * for English, Settings' US or UK), and what happens before listening (read-aloud stops, so the recognizer never
 * hears Granger's own voice; music is left playing, as the A0 spike found it harmless). The phone is asked about its
 * languages only while a screen with a mic is watching [status].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInput(
    val speech: SpeechInput,
    val prefs: VoiceInputPrefs,
    appLanguage: Flow<String>,
    private val stopReadAloud: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val generation = MutableStateFlow(0)

    /** A language model being installed from Settings → Voice input. */
    sealed interface Download {
        data object Idle : Download
        /** [percent] when the system reports it. */
        data class Running(val percent: Int? = null) : Download
        /** The system will finish it later (on Wi-Fi, typically). */
        data object Scheduled : Download
        data object Failed : Download
    }

    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download

    /** Installs the current language's model (UK English, say); keeps going if Settings is left. */
    fun downloadCurrent() {
        val tag = status.value.tag ?: return
        if (_download.value is Download.Running) return
        _download.value = Download.Running()
        scope.launch {
            val outcome = speech.download(tag) { percent -> _download.value = Download.Running(percent.coerceIn(0, 100)) }
            if (outcome == DownloadOutcome.DONE) {
                // The phone can report success a moment before it lists the language as installed (seen on the
                // Pixel 6, 2026-11-02: the mic stayed hidden after a finished en-GB download), so ask again until it does.
                for (check in 1..INSTALL_CHECKS) {
                    speech.forget()
                    if (speech.availability(tag) == Availability.READY) break
                    delay(INSTALL_CHECK_MS)
                }
            }
            _download.value = when (outcome) {
                DownloadOutcome.DONE -> Download.Idle
                DownloadOutcome.SCHEDULED -> Download.Scheduled
                DownloadOutcome.FAILED -> Download.Failed
            }
            refresh()
        }
    }

    val status: StateFlow<VoiceInputStatus> = combine(appLanguage, prefs.englishTag, generation) { language, english, _ -> language to english }
        .mapLatest { (language, english) ->
            val languages = speech.languages()
            val tag = RecognizerTags.forApp(language, english, languages)
            VoiceInputStatus(tag, tag?.let { speech.availability(it) } ?: Availability.UNAVAILABLE, phoneCanListen = languages != null)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), VoiceInputStatus())

    /** Ask the phone again (after a download, or coming back to Settings): what it said before is forgotten. */
    fun refresh() {
        speech.forget()
        generation.value++
    }

    private companion object {
        const val INSTALL_CHECKS = 5
        const val INSTALL_CHECK_MS = 1_000L
    }

    /** Called just before every take. */
    fun beforeListening() = stopReadAloud()
}
