package app.granger.voiceinput

import app.granger.R
import app.granger.UiText
import app.granger.core.speech.DictationEvent
import app.granger.core.speech.DictationFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One screen's dictation (Plan A1): owned by its view model, it knows nothing about the screen. A take can be started
 * by the mic button or from code (Ask's conversation mode, next), and its text goes to callbacks: [start]'s `onText`
 * with the words so far, `onFinal` once with the result. A failure shows for [failureShownMs], then clears.
 */
class Dictation(
    private val voiceInput: VoiceInput,
    private val scope: CoroutineScope,
    private val failureShownMs: Long = 3_000,
) {
    sealed interface State {
        data object Idle : State
        /** [partial] is empty until the first words arrive (a cold first take can take a while). */
        data class Listening(val partial: String = "") : State
        data class Failed(val message: UiText) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var take: Job? = null
    private var clear: Job? = null

    val listening: Boolean get() = _state.value is State.Listening

    /**
     * Starts a take in the mic's current language; false (and nothing starts) while one is under way or when there's
     * no mic. [onFailed] hears a take that ends without words (silence, no permission, a busy recognizer).
     */
    fun start(onText: (String) -> Unit = {}, onFailed: (DictationFailure) -> Unit = {}, onFinal: (String) -> Unit): Boolean {
        if (take?.isActive == true) return false
        val tag = voiceInput.status.value.tag ?: return false
        clear?.cancel()
        _state.value = State.Listening()
        take = scope.launch {
            voiceInput.beforeListening()
            voiceInput.speech.listen(tag).collect { event ->
                when (event) {
                    is DictationEvent.Partial -> { _state.value = State.Listening(event.text); onText(event.text) }
                    is DictationEvent.Final -> { _state.value = State.Idle; onFinal(event.text) }
                    is DictationEvent.Failed -> { fail(event.reason); onFailed(event.reason) }
                    DictationEvent.Listening, DictationEvent.EndOfSpeech -> Unit
                }
            }
            if (_state.value is State.Listening) _state.value = State.Idle
        }
        return true
    }

    /** Ends the take now instead of waiting for the recognizer to hear the end; its final text still arrives. */
    fun stop() { if (take?.isActive == true) voiceInput.speech.stop() }

    /** Drops the take; nothing more arrives. */
    fun cancel() {
        take?.cancel()
        take = null
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    private fun fail(reason: DictationFailure) {
        _state.value = State.Failed(UiText.Res(messageFor(reason)))
        clear = scope.launch {
            delay(failureShownMs)
            if (_state.value is State.Failed) _state.value = State.Idle
        }
    }

    companion object {
        fun messageFor(reason: DictationFailure): Int = when (reason) {
            DictationFailure.NO_MATCH -> R.string.voice_input_no_match
            DictationFailure.NO_PERMISSION -> R.string.voice_input_no_permission
            DictationFailure.UNAVAILABLE -> R.string.voice_input_unavailable
            DictationFailure.BUSY, DictationFailure.TIMEOUT, DictationFailure.OTHER -> R.string.voice_input_failed
        }
    }
}
