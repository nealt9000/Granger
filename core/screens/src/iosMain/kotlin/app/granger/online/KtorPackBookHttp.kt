package app.granger.online

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The iPhone's [PackBookHttp] (Plan I5 Task 7): Ktor on [client] (the Darwin engine; tests pass a mock), with Granger's
 * [userAgent] and nothing sent while [allowed] says no (the privacy line unseen, or Local only), as Android's app
 * client refuses. Redirects are followed only by [fetch], as Android's reads do.
 */
class KtorPackBookHttp(
    private val userAgent: String,
    private val allowed: () -> Boolean = { true },
    client: HttpClient = HttpClient(Darwin) { install(HttpTimeout) { socketTimeoutMillis = SOCKET_TIMEOUT_MS; connectTimeoutMillis = SOCKET_TIMEOUT_MS } },
) : PackBookHttp {
    private val direct = client.config { followRedirects = false }
    private val following = client.config { followRedirects = true }

    override suspend fun fetch(url: String, to: String): Boolean {
        if (!allowed()) return false
        return attempt(false) {
            following.prepareGet(url) { header(HttpHeaders.UserAgent, userAgent) }.execute { r ->
                if (!r.status.isSuccess()) return@execute false
                val channel = r.bodyAsChannel()
                SystemFileSystem.sink(Path(to)).buffered().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
                true
            }
        }
    }

    override suspend fun head(url: String): BookHop? {
        if (!allowed()) return null
        return attempt(null) {
            val r = direct.head(url) { header(HttpHeaders.UserAgent, userAgent) }
            BookHop(r.status.value, r.headers[HttpHeaders.Location]?.let { URLBuilder(url).takeFrom(it).buildString() })
        }
    }

    override suspend fun range(
        url: String, first: Long, last: Long, start: (RangeAnswer) -> Boolean, body: suspend (ByteArray, Int) -> Boolean,
    ): RangeAnswer? {
        if (!allowed()) return null
        return attempt(null) {
            direct.prepareGet(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Range, "bytes=$first-$last")
            }.execute { r ->
                val answer = RangeAnswer(r.status.value, r.contentLength() ?: -1L)
                if (!start(answer)) return@execute answer
                val channel = r.bodyAsChannel()
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0 || (n > 0 && !body(buf, n))) break
                }
                answer
            }
        }
    }

    private inline fun <T> attempt(none: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        none
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000L
    }
}
