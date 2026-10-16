package app.granger.core.downloads

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.security.MessageDigest

/**
 * A fake HTTP server as an OkHttp interceptor: serves [files] by URL with Range support, and can
 * ignore ranges, drop the connection after N bytes, or answer with a fixed status.
 */
class FakeServer : Interceptor {
    val files = mutableMapOf<String, ByteArray>()
    /** Served once for that URL, then [files] takes over. */
    val once = mutableMapOf<String, ByteArray>()
    val failAfter = mutableMapOf<String, Int>()
    val ignoreRange = mutableSetOf<String>()
    val status = mutableMapOf<String, Int>()
    val requests = mutableListOf<Request>()

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun rangesRequested(url: String): List<String?> =
        requests.filter { it.url.toString() == url }.map { it.header("Range") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("")
        status[url]?.let { return response.code(it).body(ByteArray(0).toResponseBody()).build() }
        val content = once.remove(url) ?: files[url]
            ?: return response.code(404).body(ByteArray(0).toResponseBody()).build()
        val start = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        val partial = start != null && url !in ignoreRange
        val bytes = if (partial) content.copyOfRange(start!!, content.size) else content
        if (partial) response.header("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
        return response
            .code(if (partial) 206 else 200)
            .body(DroppingBody(bytes, failAfter[url]))
            .build()
    }

    private class DroppingBody(private val bytes: ByteArray, private val failAfter: Int?) : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = object : Source {
            private var pos = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failAfter != null && pos >= failAfter) throw IOException("connection reset")
                if (pos >= bytes.size) return -1
                val end = minOf(bytes.size.toLong(), pos + byteCount, (failAfter ?: Int.MAX_VALUE).toLong()).toInt()
                sink.write(bytes, pos, end - pos)
                val read = end - pos
                pos = end
                return read.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
    }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
