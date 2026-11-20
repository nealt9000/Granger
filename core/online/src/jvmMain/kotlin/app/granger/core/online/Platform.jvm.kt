package app.granger.core.online

import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

actual class HostGates actual constructor(private val permits: Int) {
    private val gates = ConcurrentHashMap<String, Semaphore>()
    actual fun <T> through(host: String, block: () -> T): T {
        val gate = gates.computeIfAbsent(host) { Semaphore(permits) }
        gate.acquire()
        try { return block() } finally { gate.release() }
    }
}

actual class SyncLock actual constructor() {
    private val lock = Any()
    actual fun <T> with(block: () -> T): T = synchronized(lock) { block() }
}

internal actual fun <T, R> parallelMap(items: List<T>, threads: Int, f: (T) -> R): List<R?> {
    if (items.isEmpty()) return emptyList()
    val pool = Executors.newFixedThreadPool(threads)
    try {
        return pool.invokeAll(items.map { Callable { f(it) } }).map { runCatching { it.get() }.getOrNull() }
    } finally {
        pool.shutdown()
    }
}

internal actual fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

internal actual fun nowMillis(): Long = System.currentTimeMillis()

/**
 * Android's [HttpFetch]: the app's one `OkHttpClient`, so its network gate (Local only), cache and User-Agent apply as
 * before Plan I1. [noCache] forces the network (a random article).
 */
fun okHttpFetch(http: OkHttpClient): HttpFetch = object : HttpFetch {
    /** The same client without following redirects, for the records' signed-URL resolution; made once. */
    private val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    override fun get(url: String, noCache: Boolean): HttpReply? = send(HttpRequest(url, noCache = noCache))

    override fun send(request: HttpRequest): HttpReply? {
        val builder = Request.Builder().url(request.url)
        if (request.noCache) builder.cacheControl(CacheControl.FORCE_NETWORK)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        if (request.method == "HEAD") builder.head()
        val client = if (request.followRedirects) http else noRedirects
        return client.newCall(builder.build()).execute().use { r ->
            val body = if (r.isSuccessful && request.method != "HEAD") r.body.bytes() else ByteArray(0)
            HttpReply(r.code, body, r.request.url.toString(), r.header("Content-Type"), r.headers.toMap())
        }
    }
}

/** The client on Android's shared `OkHttpClient` (its gate, cache and User-Agent), as it was built before Plan I1. */
fun WikimediaClient(http: OkHttpClient, onMainThread: () -> Boolean = { false }, allowed: () -> Boolean = { true }): WikimediaClient =
    WikimediaClient(okHttpFetch(http), onMainThread, allowed)
