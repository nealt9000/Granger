package app.granger.core.archive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A finished GET: its status and body, whatever the status. */
internal class HttpReply(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * One GET with Granger's User-Agent, on [Dispatchers.IO], shared by the archive.org and LibriVox clients. The caller
 * maps statuses to its own errors; an [IOException] becomes [unreachable]'s error.
 *
 * A failure is tried once more on a new connection, and a call gets [CALL_TIMEOUT_S] in all. The shared client keeps
 * connections for minutes; one the network dropped meanwhile (the phone changed networks, a router forgot it) hung
 * each search for the client's 60 s read timeout and then failed, until the pool let it go: "Couldn't reach
 * archive.org" for a few minutes, then fine again (Pixel 6 walkthrough, 2026-10-30).
 */
internal suspend fun OkHttpClient.getText(url: HttpUrl, userAgent: String, unreachable: () -> Exception): HttpReply =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        // Shares this client's pool and dispatcher; only the timeout differs.
        val client = newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
        fun get() = client.newCall(request).execute().use { response -> HttpReply(response.code, response.body?.string().orEmpty()) }
        try {
            get()
        } catch (first: IOException) {
            Log.w(TAG, "GET ${url.host}${url.encodedPath} failed ($first); trying a new connection")
            connectionPool.evictAll()
            try {
                get()
            } catch (e: IOException) {
                Log.w(TAG, "GET ${url.host}${url.encodedPath} failed again ($e)")
                throw unreachable()
            }
        }
    }

private const val TAG = "GrangerHttp"
private const val CALL_TIMEOUT_S = 25L
