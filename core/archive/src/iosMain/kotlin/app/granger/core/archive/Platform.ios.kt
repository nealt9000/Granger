package app.granger.core.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

internal actual fun currentYear(): Int = NSCalendar.currentCalendar.component(NSCalendarUnitYear, NSDate()).toInt()

internal actual fun logWarn(message: String) {
    NSLog("GrangerHttp: $message".replace("%", "%%"))
}

/**
 * The iPhone's [ArchiveHttp] (Plan I5): Ktor, on [client] (the app passes one on the Darwin engine; tests a mock), with
 * Android's rules: 25 s a call, one more try on a failure, and nothing sent when [allowed] says no (Local only).
 */
class KtorArchiveHttp(
    private val client: HttpClient = HttpClient(Darwin) { install(HttpTimeout) { requestTimeoutMillis = CALL_TIMEOUT_MS } },
    private val allowed: () -> Boolean = { true },
) : ArchiveHttp {
    override suspend fun get(url: String, userAgent: String): HttpReply {
        if (!allowed()) throw Unreachable("Local only")
        suspend fun once(): HttpReply {
            val response = client.get(url) { header("User-Agent", userAgent) }
            return HttpReply(response.status.value, response.bodyAsText())
        }
        return try {
            once()
        } catch (e: CancellationException) {
            throw e
        } catch (first: Exception) {
            logWarn("GET $url failed ($first); trying again")
            try {
                once()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("GET $url failed again ($e)")
                throw Unreachable("GET failed", e)
            }
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 25_000L
    }
}
