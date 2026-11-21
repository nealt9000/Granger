package app.granger.voiceinput

import app.granger.core.speech.RecognizerTags
import kotlinx.coroutines.flow.StateFlow

// Shared since Plan I6 Task 2: Android keeps SharedVoiceInputPrefs; the iPhone's is UserDefaults (I6b).

/** Settings → Voice input (Plan A1). */
interface VoiceInputPrefs {
    /** The English the mic listens in: [RecognizerTags.ENGLISH_US] (default) or [RecognizerTags.ENGLISH_UK]. */
    val englishTag: StateFlow<String>
    /** "Ask as soon as I stop talking": a spoken question sends itself; off by default (Neal, 2026-11-02). */
    val askWhenDone: StateFlow<Boolean>
    /** Whether the microphone permission has been asked for before, to tell "not yet asked" from "refused for good". */
    val micAsked: Boolean
    fun setEnglishTag(tag: String)
    fun setAskWhenDone(on: Boolean)
    fun setMicAsked()
}
