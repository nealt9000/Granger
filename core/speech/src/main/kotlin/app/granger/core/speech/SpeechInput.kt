package app.granger.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * Speech in (Plan A1): the phone's own on-device recognizer, for dictation into the ask box and Search, and later
 * Ask's conversation mode. Nothing is recorded or sent; the recognizer runs on the phone. No biasing: the A0 spike
 * found it helps names by 5 points at best, and a title matcher after recognition does better
 * (`docs/superpowers/spikes/2026-10-voice-input-spike-results.md`).
 */
interface SpeechInput {
    /** The languages the recognizer has installed and could download; null when the phone can't say (or has none). */
    suspend fun languages(): RecognizerLanguages?

    /** Forget what the phone said about its languages, so the next [languages] asks again (after a download, say). */
    fun forget()

    /** Whether [tag] can be listened in now, after a download, or not at all. */
    suspend fun availability(tag: String): Availability

    /**
     * One take in [tag], from the microphone. Collecting starts listening; the flow ends after [DictationEvent.Final]
     * or [DictationEvent.Failed]. Cancelling the collection cancels the take. The recognizer ends the take itself when
     * it hears the end of speech; [stop] ends it at once.
     */
    fun listen(tag: String): Flow<DictationEvent>

    /** Ends the take being listened to now; its [DictationEvent.Final] follows. Does nothing when idle. */
    fun stop()

    /** Asks the system to install [tag]'s on-device model, with progress in percent where the system reports it. */
    suspend fun download(tag: String, onProgress: (Int) -> Unit = {}): DownloadOutcome
}

sealed interface DictationEvent {
    /** The microphone is open (`onReadyForSpeech`). */
    data object Listening : DictationEvent
    /** The words so far; each replaces the last. */
    data class Partial(val text: String) : DictationEvent
    /** The recognizer heard the speech end; [Final] follows shortly. */
    data object EndOfSpeech : DictationEvent
    data class Final(val text: String) : DictationEvent
    data class Failed(val reason: DictationFailure) : DictationEvent
}

enum class DictationFailure {
    /** Nothing recognizable was said. */
    NO_MATCH,
    NO_PERMISSION,
    /** The recognizer was busy or its service went away, even after one retry. */
    BUSY,
    /** The language isn't installed or the phone has no on-device recognizer. */
    UNAVAILABLE,
    /** The recognizer never opened the microphone (the slow cold start has a long allowance). */
    TIMEOUT,
    OTHER,
}

enum class Availability { READY, DOWNLOADABLE, UNAVAILABLE }

enum class DownloadOutcome { DONE, SCHEDULED, FAILED }

/** BCP 47 tags from `checkRecognitionSupport`: [installed] work now, [supported] can be downloaded. */
data class RecognizerLanguages(val installed: List<String>, val supported: List<String>) {
    fun availability(tag: String): Availability = when {
        installed.any { it.equals(tag, ignoreCase = true) } -> Availability.READY
        supported.any { it.equals(tag, ignoreCase = true) } -> Availability.DOWNLOADABLE
        else -> Availability.UNAVAILABLE
    }
}
