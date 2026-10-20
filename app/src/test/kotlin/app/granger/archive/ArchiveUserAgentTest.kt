package app.granger.archive

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ArchiveUserAgentTest {
    private val ua = "Granger/1.0 (+https://github.com/nealt9000/Granger)"

    private fun userAgentSentTo(url: String): String? {
        var sent: String? = null
        val capture = Interceptor { chain ->
            sent = chain.request().header("User-Agent")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200)
                .body("".toResponseBody()).build()
        }
        val client = OkHttpClient.Builder().addInterceptor(ArchiveUserAgent(ua)).addInterceptor(capture).build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
        return sent
    }

    @Test fun archiveHostsGetTheGrangerUserAgent() {
        assertThat(userAgentSentTo("https://archive.org/download/x/x.epub")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://ia800300.us.archive.org/12/items/x/x.epub")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://ARCHIVE.ORG/details/x")).isEqualTo(ua)
    }

    @Test fun otherHostsAreLeftAlone() {
        assertThat(userAgentSentTo("https://example.com/x.epub")).isNull()
        assertThat(userAgentSentTo("https://notarchive.org/x.epub")).isNull()
        assertThat(userAgentSentTo("https://archive.org.evil.example/x.epub")).isNull()
    }

    @Test fun librivoxGetsTheUserAgent() {
        assertThat(ArchiveUserAgent.isArchiveHost("librivox.org")).isTrue()
        assertThat(ArchiveUserAgent.isArchiveHost("www.librivox.org")).isTrue()
        assertThat(ArchiveUserAgent.isArchiveHost("librivox.org.evil.test")).isFalse()
        assertThat(ArchiveUserAgent.isArchiveHost("notlibrivox.org")).isFalse()
        assertThat(userAgentSentTo("https://librivox.org/api/feed/audiobooks/?format=json")).isEqualTo(ua)
    }
}
