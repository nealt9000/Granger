package app.granger.core.online

import com.google.common.truth.Truth.assertThat
import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** The common codec gives what the JVM's encoders gave before Plan I1. */
class UrlCodecJvmTest {
    private val samples = listOf(
        "Moon", "Albert Einstein", "C++", "AC/DC", "Question?", "Rock & roll", "100%", "Ångström", "Gödel's theorems",
        "Café_(film)", "Ἀριστοτέλης", "日本語", "😀 emoji", "a+b", "x*y", "under_score", "dot.ted", "tilde~", "comma,semi;colon:",
        "\"quotes\"", "<html>", "#hash", "[brackets]", "{braces}", "pipe|bar", "back\\slash", "",
    )

    @Test fun formEncodeIsUrlEncoder() {
        for (s in samples) assertThat(UrlCodec.formEncode(s)).isEqualTo(URLEncoder.encode(s, "UTF-8"))
    }

    @Test fun formDecodeIsUrlDecoder() {
        for (s in samples) {
            val enc = URLEncoder.encode(s, "UTF-8")
            assertThat(UrlCodec.formDecode(enc)).isEqualTo(URLDecoder.decode(enc, "UTF-8"))
            assertThat(UrlCodec.formDecode(s.replace("%", ""))).isEqualTo(URLDecoder.decode(s.replace("%", ""), "UTF-8"))
        }
    }

    @Test fun queriesReadBackAsOkHttpReadsThem() {
        for (s in samples) {
            val url = UrlCodec.withQuery("https://en.wikipedia.org/w/api.php", "titles" to s, "q" to "x")
            assertThat(url.toHttpUrl().queryParameter("titles")).isEqualTo(s)
        }
    }

    @Test fun pathSegmentsAreOkHttps() {
        val url = "https://en.wikipedia.org/w/rest.php/v1/page/" + WikimediaClient.enc("AC/DC (band)") + "/html"
        assertThat(UrlCodec.pathSegments(url)).isEqualTo(url.toHttpUrl().pathSegments)
        assertThat(UrlCodec.hostOf(url)).isEqualTo("en.wikipedia.org")
    }
}
