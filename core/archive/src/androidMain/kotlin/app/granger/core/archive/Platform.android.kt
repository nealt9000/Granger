package app.granger.core.archive

import android.util.Log
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun currentYear(): Int = LocalDate.now().year

// As before Plan I5: a plain Log.w (a no-op in this module's host tests, which return default values).
internal actual fun logWarn(message: String) {
    Log.w(TAG, message)
}

/**
 * Android's [ArchiveHttp]: one GET on the app's [client] (its Local only gate, pool and User-Agent interceptor), on
 * [io] (tests pass their own, so no reply lands after a test ends), exactly as before Plan I5.
 *
 * A failure is tried once more on a new connection, and a call gets [CALL_TIMEOUT_S] in all. The shared client keeps
 * connections for minutes; one the network dropped meanwhile (the phone changed networks, a router forgot it) hung
 * each search for the client's 60 s read timeout and then failed, until the pool let it go: "Couldn't reach
 * archive.org" for a few minutes, then fine again (Pixel 6 walkthrough, 2026-10-30).
 */
fun okHttpArchiveHttp(client: OkHttpClient, io: CoroutineDispatcher = Dispatchers.IO): ArchiveHttp = ArchiveHttp { url, userAgent ->
    withContext(io) {
        val parsed = url.toHttpUrl()
        val request = Request.Builder().url(parsed).header("User-Agent", userAgent).build()
        // Shares this client's pool and dispatcher; only the timeout differs.
        val timed = client.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()
        fun get() = timed.newCall(request).execute().use { response -> HttpReply(response.code, response.body?.string().orEmpty()) }
        try {
            get()
        } catch (first: IOException) {
            logWarn("GET ${parsed.host}${parsed.encodedPath} failed ($first); trying a new connection")
            client.connectionPool.evictAll()
            try {
                get()
            } catch (e: IOException) {
                logWarn("GET ${parsed.host}${parsed.encodedPath} failed again ($e)")
                throw Unreachable("GET ${parsed.host} failed", e)
            }
        }
    }
}

/** The archive.org client on the app's [client], as Android built it before Plan I5. */
fun ArchiveOrgClient(
    client: OkHttpClient,
    userAgent: String,
    baseUrl: String = "https://archive.org",
    io: CoroutineDispatcher = Dispatchers.IO,
): ArchiveOrgClient = ArchiveOrgClient(okHttpArchiveHttp(client, io), userAgent, baseUrl)

/** The LibriVox client on the app's [client], as Android built it before Plan I5. */
fun LibriVoxClient(
    client: OkHttpClient,
    userAgent: String,
    baseUrl: String = "https://librivox.org",
    minGapMs: Long = 1_000,
    clock: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): LibriVoxClient = LibriVoxClient(okHttpArchiveHttp(client), userAgent, baseUrl, minGapMs, clock, sleep)

// Android's date forms of the shared rules (they read only the year).

fun PublicDomain.lastRecordingYear(today: LocalDate): Int = lastRecordingYear(today.year)

fun PublicDomain.clause(kind: ArchiveKind, today: LocalDate): String = clause(kind, today.year)

fun ArchiveOffers.isPublicDomainRecording(item: ArchiveItem, today: LocalDate): Boolean = isPublicDomainRecording(item, today.year)

fun ArchiveQuery.build(
    text: String,
    shelf: ArchiveShelf?,
    filtered: Boolean,
    kind: ArchiveKind,
    today: LocalDate,
    languages: List<String> = listOf("en"),
): String = build(text, shelf, filtered, kind, today.year, languages)

private const val TAG = "GrangerHttp"
private const val CALL_TIMEOUT_S = 25L
