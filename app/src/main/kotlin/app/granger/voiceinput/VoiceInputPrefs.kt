package app.granger.voiceinput

import android.content.Context
import android.content.SharedPreferences
import app.granger.core.speech.RecognizerTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

class SharedVoiceInputPrefs(private val prefs: SharedPreferences) : VoiceInputPrefs {
    constructor(context: Context) : this(context.getSharedPreferences("voice_input", Context.MODE_PRIVATE))

    private val english = MutableStateFlow(
        prefs.getString(KEY_ENGLISH, null)?.takeIf { it == RecognizerTags.ENGLISH_UK } ?: RecognizerTags.ENGLISH_US,
    )
    private val askDone = MutableStateFlow(prefs.getBoolean(KEY_ASK_WHEN_DONE, false))

    override val englishTag: StateFlow<String> = english
    override val askWhenDone: StateFlow<Boolean> = askDone
    override val micAsked: Boolean get() = prefs.getBoolean(KEY_MIC_ASKED, false)

    override fun setEnglishTag(tag: String) {
        val value = if (tag == RecognizerTags.ENGLISH_UK) RecognizerTags.ENGLISH_UK else RecognizerTags.ENGLISH_US
        prefs.edit().putString(KEY_ENGLISH, value).apply()
        english.value = value
    }

    override fun setAskWhenDone(on: Boolean) {
        prefs.edit().putBoolean(KEY_ASK_WHEN_DONE, on).apply()
        askDone.value = on
    }

    override fun setMicAsked() { prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply() }

    private companion object {
        const val KEY_ENGLISH = "english"
        const val KEY_ASK_WHEN_DONE = "ask_when_done"
        const val KEY_MIC_ASKED = "mic_asked"
    }
}
