package app.granger.archive

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends Granger's User-Agent on every request the shared client makes (Plan O1 builder's call 5: one agent for every
 * host). Wikimedia and archive.org both ask for a contactable agent; it names the project's own site and mailbox, never
 * the user. Installed as a network interceptor, so file downloads and the redirects they follow (to
 * `ia800….us.archive.org`, `release-assets.githubusercontent.com`) carry it too.
 */
class GrangerUserAgent(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())

    companion object {
        /** `Granger/<version> (https://opengranger.org; feedback@opengranger.org)`, as Wikimedia's policy asks. */
        fun of(version: String) = "Granger/$version (https://opengranger.org; feedback@opengranger.org)"
    }
}
