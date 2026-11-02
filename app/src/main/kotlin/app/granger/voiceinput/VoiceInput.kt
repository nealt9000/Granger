package app.granger.voiceinput

import app.granger.core.speech.Availability
import app.granger.core.speech.RecognizerTags
import app.granger.core.speech.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Which recognizer language the mic would listen in now ([tag], null for none) and whether it's installed. */
data class VoiceInputStatus(val tag: String? = null, val availability: Availability = Availability.UNAVAILABLE) {
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
    scope: CoroutineScope,
) {
    private val generation = MutableStateFlow(0)

    val status: StateFlow<VoiceInputStatus> = combine(appLanguage, prefs.englishTag, generation) { language, english, _ -> language to english }
        .mapLatest { (language, english) ->
            val tag = RecognizerTags.forApp(language, english, speech.languages())
            VoiceInputStatus(tag, tag?.let { speech.availability(it) } ?: Availability.UNAVAILABLE)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), VoiceInputStatus())

    /** Ask the phone again (after a download, or coming back from its settings). */
    fun refresh() { generation.value++ }

    /** Called just before every take. */
    fun beforeListening() = stopReadAloud()
}
