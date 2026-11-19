package app.granger.online

import android.content.Context
import app.granger.core.downloads.NetworkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app may go online (spec §7, §9), kept across restarts: Local only stays on until it's turned off. O1 has
 * no switch on screen (O3 adds the top bar's and the quick settings tile, O5 Settings → Online); debug builds set it from
 * the `local_only` launch extra.
 */
class NetworkPolicy(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("online", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        runCatching { NetworkMode.valueOf(prefs.getString(KEY, null) ?: NetworkMode.ONLINE.name) }.getOrDefault(NetworkMode.ONLINE),
    )
    val mode: StateFlow<NetworkMode> = state.asStateFlow()

    /** Read on every request, from any thread. */
    fun now(): NetworkMode = state.value

    fun set(mode: NetworkMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        state.value = mode
    }

    private companion object {
        const val KEY = "mode"
    }
}
