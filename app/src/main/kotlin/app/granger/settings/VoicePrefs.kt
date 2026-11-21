package app.granger.settings

import android.content.Context
import android.content.SharedPreferences
import app.granger.core.speech.VoiceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The VoicePrefs interface is shared since Plan I5 Task 8 (core:screens).

class SharedVoicePrefs(private val prefs: SharedPreferences) : VoicePrefs {
    constructor(context: Context) : this(context.getSharedPreferences("voice", Context.MODE_PRIVATE))

    private val voice = MutableStateFlow(prefs.getString(KEY_VOICE, null)?.let(VoiceId::decode))
    private val rate = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED))
    private val mark = MutableStateFlow(prefs.getBoolean(KEY_MARK, true))
    private val speak = MutableStateFlow(prefs.getBoolean(KEY_SPEAK_ANSWERS, false))

    override val voiceId: StateFlow<VoiceId?> = voice
    override val speed: StateFlow<Float> = rate
    override val markSentence: StateFlow<Boolean> = mark
    override val speakAnswers: StateFlow<Boolean> = speak

    override fun setVoice(id: VoiceId?) {
        prefs.edit().apply { if (id == null) remove(KEY_VOICE) else putString(KEY_VOICE, id.encode()) }.apply()
        voice.value = id
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        prefs.edit().putFloat(KEY_SPEED, s).apply()
        rate.value = s
    }

    override fun setMarkSentence(on: Boolean) {
        prefs.edit().putBoolean(KEY_MARK, on).apply()
        mark.value = on
    }

    override fun setSpeakAnswers(on: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_ANSWERS, on).apply()
        speak.value = on
    }

    companion object {
        const val MIN_SPEED = VoicePrefs.MIN_SPEED
        const val MAX_SPEED = VoicePrefs.MAX_SPEED
        private const val KEY_VOICE = "voiceId"
        private const val KEY_SPEED = "speed"
        private const val KEY_MARK = "markSentence"
        private const val KEY_SPEAK_ANSWERS = "speakAnswers"
    }
}
