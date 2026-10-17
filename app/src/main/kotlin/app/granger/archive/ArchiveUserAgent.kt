package app.granger.archive

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends Granger's User-Agent on every request to archive.org or one of its hosts. Installed as a network interceptor
 * on the shared client, so file downloads and the redirects they follow (to `ia800….us.archive.org`) carry it too.
 */
class ArchiveUserAgent(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isArchiveHost(request.url.host)) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
    }

    companion object {
        /** `archive.org` or a subdomain of it; OkHttp hands hosts over lower-cased. */
        fun isArchiveHost(host: String): Boolean = host == "archive.org" || host.endsWith(".archive.org")
    }
}
