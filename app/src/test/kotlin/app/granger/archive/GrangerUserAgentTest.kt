package app.granger.archive

import app.granger.core.downloads.LocalOnly
import app.granger.core.downloads.NetworkGate
import app.granger.core.downloads.NetworkMode
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

class GrangerUserAgentTest {
    private val ua = GrangerUserAgent.of("1.0")
    private var mode = NetworkMode.ONLINE
    private var reached = 0

    private fun client(): OkHttpClient {
        val capture = Interceptor { chain ->
            reached++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200)
                .header("X-UA", chain.request().header("User-Agent").orEmpty())
                .body("".toResponseBody()).build()
        }
        // As the app builds its shared client: the gate first, the agent on every request.
        return OkHttpClient.Builder().addInterceptor(NetworkGate { mode }).addInterceptor(GrangerUserAgent(ua)).addInterceptor(capture).build()
    }

    private fun userAgentSentTo(url: String): String? =
        client().newCall(Request.Builder().url(url).build()).execute().use { it.header("X-UA") }

    @Test fun theAgentNamesTheProjectNeverTheUser() {
        assertThat(ua).isEqualTo("Granger/1.0 (https://opengranger.org; feedback@opengranger.org)")
    }

    @Test fun everyHostGetsTheGrangerUserAgent() {
        assertThat(userAgentSentTo("https://archive.org/download/x/x.epub")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://ia800300.us.archive.org/12/items/x/x.epub")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://librivox.org/api/feed/audiobooks/?format=json")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://en.wikipedia.org/w/rest.php/v1/page/Moon/html")).isEqualTo(ua)
        assertThat(userAgentSentTo("https://upload.wikimedia.org/wikipedia/commons/x.jpg")).isEqualTo(ua)
    }

    @Test fun localOnlyAndNeverRefuseBeforeAnyConnection() {
        for (m in listOf(NetworkMode.LOCAL_ONLY, NetworkMode.NEVER)) {
            mode = m
            val e = assertThrows(LocalOnly::class.java) {
                client().newCall(Request.Builder().url("https://en.wikipedia.org/wiki/Moon").build()).execute()
            }
            assertThat(e.host).isEqualTo("en.wikipedia.org")
            assertThat(e).isInstanceOf(java.io.IOException::class.java)
        }
        assertThat(reached).isEqualTo(0)
        mode = NetworkMode.ONLINE
        assertThat(userAgentSentTo("https://en.wikipedia.org/wiki/Moon")).isEqualTo(ua)
        assertThat(reached).isEqualTo(1)
    }
}
