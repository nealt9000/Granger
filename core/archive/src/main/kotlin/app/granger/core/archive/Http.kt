package app.granger.core.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A finished GET: its status and body, whatever the status. */
internal class HttpReply(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * One GET with Granger's User-Agent, on [Dispatchers.IO], shared by the archive.org and LibriVox clients. The caller
 * maps statuses to its own errors; an [IOException] becomes [unreachable]'s error.
 */
internal suspend fun OkHttpClient.getText(url: HttpUrl, userAgent: String, unreachable: () -> Exception): HttpReply =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        try {
            newCall(request).execute().use { response -> HttpReply(response.code, response.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw unreachable()
        }
    }
