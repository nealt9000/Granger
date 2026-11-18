package app.granger.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings' "Keep adult topics out of suggestions" (roadmap, "Carried forward from EX", Neal 2026-11-18): on by default.
 * Off, the screens Granger fills by itself show flagged topics and pictures like any other.
 */
interface ContentPrefs {
    val keepOff: StateFlow<Boolean>
    fun setKeepOff(on: Boolean)

    class Memory(start: Boolean = true) : ContentPrefs {
        private val state = MutableStateFlow(start)
        override val keepOff: StateFlow<Boolean> = state.asStateFlow()
        override fun setKeepOff(on: Boolean) { state.value = on }
    }

    class Prefs(private val prefs: SharedPreferences) : ContentPrefs {
        private val state = MutableStateFlow(prefs.getBoolean(KEEP_OFF, true))
        override val keepOff: StateFlow<Boolean> = state.asStateFlow()
        override fun setKeepOff(on: Boolean) {
            prefs.edit().putBoolean(KEEP_OFF, on).apply()
            state.value = on
        }

        private companion object {
            const val KEEP_OFF = "keep_adult_off"
        }
    }
}
