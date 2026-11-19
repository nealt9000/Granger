package app.granger.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.granger.core.downloads.NetworkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether Granger can reach the internet now (Plan O3 Task 1; builder's call 1): the network policy (Local only, Never)
 * and Android's own say (a validated connection). Offline looks the same either way; only the banner's words differ.
 */
enum class ReachState {
    ONLINE, NO_SIGNAL, LOCAL_ONLY, NEVER;

    val online: Boolean get() = this == ONLINE

    companion object {
        /** The table (pure, for tests): the policy wins; with the policy open, the connection decides. */
        fun of(mode: NetworkMode, connected: Boolean): ReachState = when (mode) {
            NetworkMode.LOCAL_ONLY -> LOCAL_ONLY
            NetworkMode.NEVER -> NEVER
            NetworkMode.ONLINE -> if (connected) ONLINE else NO_SIGNAL
        }
    }
}

/** [ReachState] as it changes: the policy and a default-network callback with a validated internet capability. */
class Reach(context: Context, mode: StateFlow<NetworkMode>, scope: CoroutineScope) {
    private val connected = MutableStateFlow(true)

    val state: StateFlow<ReachState> =
        combine(mode, connected) { m, c -> ReachState.of(m, c) }.stateIn(scope, SharingStarted.Eagerly, ReachState.of(mode.value, true))

    init {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            connected.value = cm.getNetworkCapabilities(cm.activeNetwork)?.let(::validated) == true
            runCatching {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { connected.value = validated(caps) }
                    override fun onLost(network: Network) { connected.value = false }
                })
            }
        }
    }

    val now: ReachState get() = state.value

    private companion object {
        fun validated(caps: NetworkCapabilities) =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Whether an article in [availability] opens now (Plan O3): on the phone or saved for now always; online only when online. */
fun openable(availability: Availability, reach: ReachState): Boolean = when (availability) {
    is Availability.OnPhone, Availability.Saved -> true
    Availability.Online -> reach.online
    Availability.Nowhere -> false
}
