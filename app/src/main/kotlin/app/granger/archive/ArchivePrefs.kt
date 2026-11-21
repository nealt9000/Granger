package app.granger.archive

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android's [ArchivePrefs] (the interface is shared since Plan I5): the waiver's time in the "archive" SharedPreferences. */
class SharedArchivePrefs(context: Context) : ArchivePrefs {
    private val prefs = context.getSharedPreferences("archive", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getLong(KEY, 0L) != 0L)

    override val waiverAccepted: StateFlow<Boolean> = state

    override fun accept() {
        prefs.edit().putLong(KEY, System.currentTimeMillis()).apply()
        state.value = true
    }

    override fun withdraw() {
        prefs.edit().putLong(KEY, 0L).apply()
        state.value = false
    }

    private companion object { const val KEY = "waiverAcceptedAt" }
}
