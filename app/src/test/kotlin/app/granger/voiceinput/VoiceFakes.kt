package app.granger.voiceinput

import app.granger.core.speech.Availability
import app.granger.core.speech.DictationEvent
import app.granger.core.speech.DownloadOutcome
import app.granger.core.speech.RecognizerLanguages
import app.granger.core.speech.RecognizerTags
import app.granger.core.speech.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion

/** Test doubles for voice input (Plan A1), shared by the dictation, Search and ask-box tests. */
/** A recognizer whose events the test sends; each take is a fresh channel. */
class FakeSpeech(var languages: RecognizerLanguages? = RecognizerLanguages(listOf("en-US", "es-ES"), listOf("en-GB"))) : SpeechInput {
    var events = Channel<DictationEvent>(Channel.UNLIMITED)
    val tags = mutableListOf<String>()
    var stops = 0
    var cancelled = false
    override suspend fun languages(): RecognizerLanguages? {
        pendingInstall?.let { tag -> if (installLag-- <= 0) { install(tag); pendingInstall = null } }
        return languages
    }
    override suspend fun availability(tag: String) = languages()?.availability(tag) ?: Availability.UNAVAILABLE
    override fun listen(tag: String): Flow<DictationEvent> {
        tags += tag
        val take = Channel<DictationEvent>(Channel.UNLIMITED).also { events = it }
        return take.consumeAsFlow().onCompletion { if (it != null) cancelled = true }
    }
    override fun stop() { stops++ }
    var forgets = 0
    override fun forget() { forgets++ }
    var downloadOutcome = DownloadOutcome.DONE
    /** After a finished download, how many more language checks still say "not installed" (the Pixel 6 lags). */
    var installLag = 0
    private var pendingInstall: String? = null
    override suspend fun download(tag: String, onProgress: (Int) -> Unit): DownloadOutcome {
        onProgress(50)
        if (downloadOutcome == DownloadOutcome.DONE) {
            if (installLag == 0) install(tag) else pendingInstall = tag
        }
        return downloadOutcome
    }
    private fun install(tag: String) { languages = languages?.let { it.copy(installed = it.installed + tag) } }
    fun send(vararg e: DictationEvent) = e.forEach { events.trySend(it) }
    fun end(vararg e: DictationEvent) { send(*e); events.close() }
}

class FakePrefs : VoiceInputPrefs {
    override val englishTag = MutableStateFlow(RecognizerTags.ENGLISH_US)
    override val askWhenDone = MutableStateFlow(false)
    override var micAsked = false
    override fun setEnglishTag(tag: String) { englishTag.value = tag }
    override fun setAskWhenDone(on: Boolean) { askWhenDone.value = on }
    override fun setMicAsked() { micAsked = true }
}


/** A [VoiceInput] over [speech] in [language], counting read-aloud stops; its status is watched in [scope]. */
fun fakeVoiceInput(scope: CoroutineScope, speech: FakeSpeech = FakeSpeech(), language: String = "en", prefs: FakePrefs = FakePrefs(), onStopReadAloud: () -> Unit = {}) =
    VoiceInput(speech, prefs, MutableStateFlow(language), onStopReadAloud, scope)
