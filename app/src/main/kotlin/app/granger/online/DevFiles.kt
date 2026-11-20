package app.granger.online

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug builds only (`-PdevFilesUrl`): a request to `https://granger-dev.invalid/<file>` goes to [base]`<file>` instead,
 * so a locally signed catalog can name sidecars (which must be https) that are served from the Mac before they're on a
 * release (Plan BK's emulator pass). Every other request passes untouched.
 */
class DevFiles(private val base: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != HOST) return chain.proceed(req)
        val to = (base.trimEnd('/') + req.url.encodedPath).toHttpUrl()
        return chain.proceed(req.newBuilder().url(to).build())
    }

    companion object {
        const val HOST = "granger-dev.invalid"
    }
}
