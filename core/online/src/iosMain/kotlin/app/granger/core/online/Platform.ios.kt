package app.granger.core.online

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_t
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalForeignApi::class)
actual class HostGates actual constructor(private val permits: Int) {
    private val lock = NSLock()
    private val gates = HashMap<String, dispatch_semaphore_t>()
    actual fun <T> through(host: String, block: () -> T): T {
        lock.lock()
        val gate = gates.getOrPut(host) { dispatch_semaphore_create(permits.convert()) }
        lock.unlock()
        dispatch_semaphore_wait(gate, DISPATCH_TIME_FOREVER)
        try { return block() } finally { dispatch_semaphore_signal(gate) }
    }
}

actual class SyncLock actual constructor() {
    private val lock = NSLock()
    actual fun <T> with(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

internal actual fun <T, R> parallelMap(items: List<T>, threads: Int, f: (T) -> R): List<R?> {
    if (items.isEmpty()) return emptyList()
    val pool = Dispatchers.Default.limitedParallelism(threads)
    return runBlocking { items.map { item -> async(pool) { runCatching { f(item) }.getOrNull() } }.awaitAll() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun md5(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_MD5_DIGEST_LENGTH)
    bytes.usePinned { b -> digest.usePinned { d -> @Suppress("DEPRECATION") CC_MD5(if (bytes.isEmpty()) null else b.addressOf(0), bytes.size.convert(), d.addressOf(0)) } }
    return digest.asByteArray()
}

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * iOS's [HttpFetch]: a blocking `NSURLSession` request (callers are off the main thread, as on Android). The app sets
 * the session's User-Agent; [noCache] skips the URL cache.
 */
@OptIn(ExperimentalForeignApi::class)
class UrlSessionFetch(private val session: NSURLSession = NSURLSession.sharedSession, private val userAgent: String? = null) : HttpFetch {
    override fun get(url: String, noCache: Boolean): HttpReply? {
        val nsUrl = NSURL.URLWithString(url) ?: return null
        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        if (noCache) request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        userAgent?.let { request.setValue(it, forHTTPHeaderField = "User-Agent") }
        var reply: HttpReply? = null
        val done = dispatch_group_create()
        dispatch_group_enter(done)
        session.dataTaskWithRequest(request) { data, response, error ->
            val http = response as? NSHTTPURLResponse
            if (error == null && http != null) {
                val body = ByteArray(data?.length?.toInt() ?: 0)
                if (body.isNotEmpty()) body.usePinned { memcpy(it.addressOf(0), data!!.bytes, data.length) }
                reply = HttpReply(http.statusCode.toInt(), body, http.URL?.absoluteString ?: url, http.valueForHTTPHeaderField("Content-Type"))
            }
            dispatch_group_leave(done)
        }.resume()
        dispatch_group_wait(done, DISPATCH_TIME_FOREVER)
        return reply
    }
}
