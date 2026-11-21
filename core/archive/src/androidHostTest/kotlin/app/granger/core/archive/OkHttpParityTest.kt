package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Test

/** The shared URL code against OkHttp itself, which Android used before Plan I5. */
class OkHttpParityTest {
    private val samples = listOf(
        "mediatype:texts AND (steam engine) AND year:[* TO 1930]", "{id,title,authors}", "a+b c%d", "poesía OR teatro",
        "\"field recordings\"", "x&y=z#?/\\^`|~!$'@;", "tab\there", "日本語", "",
    )

    @Test fun queryStringsMatch() {
        for (s in samples) {
            val ok = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", s).addQueryParameter("fl[]", "identifier").build().toString()
            assertThat(urlWithQuery("https://archive.org/advancedsearch.php", listOf("q" to s, "fl[]" to "identifier"))).isEqualTo(ok)
        }
    }

    @Test fun hostsAndSegmentsMatch() {
        val urls = listOf(
            "https://www.archive.org/details/time_machine_1012_librivox", "http://ARCHIVE.org/details/x/", "https://x.org",
            "https://archive.org/download/id/a%20b_64kb.mp3", "https://user:pw@archive.org:8080/details/y?q=1#f",
            "https://archive.org/a/./b/../c", "https://archive.org/details/caf%C3%A9",
        )
        for (u in urls) {
            val ok = u.toHttpUrlOrNull()!!
            val ours = WebUrl.parse(u)!!
            assertThat(ours.host).isEqualTo(ok.host)
            assertThat(ours.pathSegments).isEqualTo(ok.pathSegments)
        }
        for (bad in listOf("ftp://x/y", "nothing", "https://")) {
            assertThat(WebUrl.parse(bad)).isNull()
            assertThat(bad.toHttpUrlOrNull()).isNull()
        }
    }
}
