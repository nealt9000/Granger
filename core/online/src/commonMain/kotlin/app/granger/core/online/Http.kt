package app.granger.core.online

/** One HTTP answer: its status, body, the URL it ended at (after redirects), its content type and its headers. */
class HttpReply(
    val code: Int,
    val body: ByteArray,
    val url: String,
    val contentType: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = code in 200..299
    val isRedirect: Boolean get() = code in 300..399
    fun text(): String = body.decodeToString()

    /** A header by name, any case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** A request beyond a plain GET (Plan I3: the topic records' Range reads and HEAD, without following redirects). */
class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val followRedirects: Boolean = true,
    val noCache: Boolean = false,
)

/**
 * A blocking GET (Plan I1). Android passes the app's shared `OkHttpClient` ([okHttpFetch]), so its network gate (Local
 * only), cache and User-Agent apply unchanged; iOS uses `NSURLSession`. Null when there's no answer at all (no signal,
 * refused by the gate); a non-2xx answer comes back with its code.
 */
fun interface HttpFetch {
    fun get(url: String, noCache: Boolean): HttpReply?

    /** Any request; the default handles a plain GET only (both phones' fetches handle the rest). */
    fun send(request: HttpRequest): HttpReply? =
        if (request.method == "GET" && request.headers.isEmpty() && request.followRedirects) get(request.url, request.noCache) else null
}

/** At most [permits] calls through [through] at once per host (Wikimedia's etiquette: a few per host). */
expect class HostGates(permits: Int) {
    fun <T> through(host: String, block: () -> T): T
}

/** Where the online code says what it's doing; the app points it at its log (Android: `Log`, debug builds). */
object OnlineLog {
    var info: (String) -> Unit = {}
    var warn: (String) -> Unit = {}
}

/** [items] mapped [threads] at a time, order kept; a failed item gives null. Blocking. */
internal expect fun <T, R> parallelMap(items: List<T>, threads: Int, f: (T) -> R): List<R?>

internal expect fun md5(bytes: ByteArray): ByteArray

internal expect fun nowMillis(): Long

/** A plain mutual-exclusion lock (common Kotlin has no `synchronized`). */
expect class SyncLock() {
    fun <T> with(block: () -> T): T
}
