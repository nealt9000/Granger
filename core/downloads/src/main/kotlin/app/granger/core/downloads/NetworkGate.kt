package app.granger.core.downloads

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Whether the app may use the internet (online/offline spec §4, §7, §9). */
enum class NetworkMode {
    /** The default: everything online is used. */
    ONLINE,
    /** The Local only switch (O3): the phone behaves exactly as with no signal, until it's turned off. */
    LOCAL_ONLY,
    /** Settings → Online → Never go online (O5): Local only that the top bar can't turn off. */
    NEVER,
}

/**
 * The one gate for the network (spec §4): installed first on the app's shared OkHttp client, it refuses every request
 * before a connection is made when [mode] isn't [NetworkMode.ONLINE]. Every feature reaches the network through that
 * client (the reader's WebView serves only what the app hands it, and the player plays local files), so Local only is
 * honest by construction: no feature can forget to check it. A refusal is an [IOException], which every caller already
 * treats as "no signal"; a download takes it as a failure worth retrying, never a permanent one.
 */
class NetworkGate(private val mode: () -> NetworkMode) : Interceptor {
    val open: Boolean get() = mode() == NetworkMode.ONLINE

    override fun intercept(chain: Interceptor.Chain): Response {
        val m = mode()
        if (m != NetworkMode.ONLINE) throw LocalOnly(chain.request().url.host, m)
        return chain.proceed(chain.request())
    }
}

/** A request the [NetworkGate] refused. */
class LocalOnly(val host: String, val mode: NetworkMode) : IOException("Local only: not asking $host")
