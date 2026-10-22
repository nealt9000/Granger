package app.granger.settings

import android.content.Context
import android.content.SharedPreferences
import app.granger.core.speech.VoiceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings → Voice (Plan V1). */
interface VoicePrefs {
    /** The voice to read with; null means the first installed (a Granger voice before the system's). */
    val voiceId: StateFlow<VoiceId?>
    /** 0.7–2.0; 1.0 by default. */
    val speed: StateFlow<Float>
    /** Mark the sentence being read on the page; on by default. */
    val markSentence: StateFlow<Boolean>
    fun setVoice(id: VoiceId?)
    fun setSpeed(speed: Float)
    fun setMarkSentence(on: Boolean)
}

class SharedVoicePrefs(private val prefs: SharedPreferences) : VoicePrefs {
    constructor(context: Context) : this(context.getSharedPreferences("voice", Context.MODE_PRIVATE))

    private val voice = MutableStateFlow(prefs.getString(KEY_VOICE, null)?.let(VoiceId::decode))
    private val rate = MutableStateFlow(prefs.getFloat(KEY_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED))
    private val mark = MutableStateFlow(prefs.getBoolean(KEY_MARK, true))

    override val voiceId: StateFlow<VoiceId?> = voice
    override val speed: StateFlow<Float> = rate
    override val markSentence: StateFlow<Boolean> = mark

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

    companion object {
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 2.0f
        private const val KEY_VOICE = "voiceId"
        private const val KEY_SPEED = "speed"
        private const val KEY_MARK = "markSentence"
    }
}
