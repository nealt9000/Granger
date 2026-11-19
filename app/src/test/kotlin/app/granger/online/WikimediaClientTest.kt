package app.granger.online

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Plan O1 Task 2: Wikimedia's answers, recorded 2026-11-19 under `resources/online/`, never the live site. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikimediaClientTest {
    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("online/$name")!!.readText()

    private val asked = mutableListOf<String>()

    /** Answers by URL: [routes] maps a substring of the URL to (status, body). */
    private fun client(routes: Map<String, Pair<Int, String>>, gate: Interceptor? = null): WikimediaClient {
        val server = Interceptor { chain ->
            val url = chain.request().url.toString()
            asked += url
            val (code, body) = routes.entries.firstOrNull { url.contains(it.key) }?.value ?: (404 to "{}")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(code)
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val http = OkHttpClient.Builder().apply { gate?.let(::addInterceptor) }.addInterceptor(server).build()
        return WikimediaClient(http)
    }

    @Test fun aSummaryCarriesTitleLeadPictureAndQid() {
        val s = client(mapOf("/page/summary/Moon" to (200 to fixture("moon-summary.json")))).summary("en", "Moon")!!
        assertThat(s.title).isEqualTo("Moon")
        assertThat(s.key).isEqualTo("Moon")
        assertThat(s.qid).isEqualTo(405L)
        assertThat(s.revision).isGreaterThan(0L)
        assertThat(s.extract).startsWith("The Moon is")
        // The tracking query goes: the same picture without it.
        assertThat(s.thumbnail).startsWith("https://")
        assertThat(s.thumbnail).doesNotContain("utm_")
    }

    @Test fun spanishSummariesComeFromSpanishWikipedia() {
        val c = client(mapOf("es.wikipedia.org/api/rest_v1/page/summary/Luna" to (200 to fixture("luna-summary.json"))))
        assertThat(c.summary("es", "Luna")!!.qid).isEqualTo(405L)
        assertThat(asked.single()).startsWith("https://es.wikipedia.org/")
    }

    @Test fun aMissingPageIsNullNotAnError() {
        assertThat(client(emptyMap()).summary("en", "Nonexistent_page_zzqx")).isNull()
        assertThat(client(emptyMap()).html("en", "Nonexistent_page_zzqx")).isNull()
    }

    @Test fun titlesAreEncodedAsOnePathSegment() {
        client(emptyMap()).summary("en", "AC/DC: Live? (album)")
        assertThat(asked.single()).endsWith("/page/summary/AC%2FDC%3A%20Live%3F%20%28album%29".replace("%20", "_"))
    }

    @Test fun searchHitsCarryKeyTitleAndPlainExcerpt() {
        val c = client(mapOf("search/title" to (200 to fixture("search-title-moon.json")), "search/page" to (200 to fixture("search-page-moon.json"))))
        val titles = c.titles("en", "moon", 5)
        assertThat(titles.first().key).isEqualTo("Moon")
        val pages = c.pages("en", "moon landing", 5)
        assertThat(pages.first().title).isEqualTo("Moon landing")
        assertThat(pages.first().excerpt).doesNotContain("searchmatch")
        assertThat(pages.first().excerpt).contains("Moon landing")
    }

    @Test fun relatedIsInSearchOrderWithQids() {
        val r = client(mapOf("morelike" to (200 to fixture("morelike-knowth.json")))).related("en", "Knowth", 8)
        assertThat(r.first().title).isEqualTo("Dowth")   // index 1
        assertThat(r.first().qid).isEqualTo(1253443L)
        assertThat(r.map { it.key }).contains("Brú_na_Bóinne")
    }

    @Test fun summariesInOneRequestByTheTitlesAsked() {
        val c = client(mapOf("prop=extracts" to (200 to fixture("batch-summaries.json"))))
        val s = c.summaries("en", listOf("Moon", "Earth's_moon", "Knowth", "Nonexistent_zzqx", "Dowth"))
        assertThat(asked).hasSize(1)
        // A redirect answers for its target; a missing page is left out.
        assertThat(s.keys).containsExactly("Moon", "Earth's_moon", "Knowth", "Dowth")
        assertThat(s.getValue("Earth's_moon").title).isEqualTo("Moon")
        assertThat(s.getValue("Moon").qid).isEqualTo(405L)
        assertThat(s.getValue("Dowth").thumbnail).startsWith("https://")
        assertThat(s.getValue("Dowth").thumbnail).doesNotContain("utm_")
        // The removed pronunciation's husk goes.
        assertThat(s.getValue("Knowth").extract).startsWith("Knowth (Irish: ")
    }

    @Test fun moreThanTwentyTitlesGoInSeveralRequests() {
        client(emptyMap()).summaries("en", (1..45).map { "T$it" })
        assertThat(asked).hasSize(3)
    }

    @Test fun picturesOnlyFromWikimedia() {
        val c = client(mapOf("upload.wikimedia.org" to (200 to "img")))
        assertThat(c.picture("https://upload.wikimedia.org/x.jpg")).isNotNull()
        assertThat(c.picture("https://example.com/x.jpg")).isNull()
        assertThat(asked.none { it.contains("example.com") }).isTrue()
    }

    @Test fun aRefusedRequestIsNoAnswer() {
        val gate = app.granger.core.downloads.NetworkGate { app.granger.core.downloads.NetworkMode.LOCAL_ONLY }
        val c = client(mapOf("/page/summary/Moon" to (200 to fixture("moon-summary.json"))), gate)
        assertThat(c.summary("en", "Moon")).isNull()
        assertThat(c.titles("en", "moon", 5)).isEmpty()
        assertThat(asked).isEmpty()
    }

    @Test fun nothingIsAskedBeforeThePrivacyLineIsSeen() {
        var seen = false
        val server = Interceptor { chain -> asked += chain.request().url.toString(); Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200).body(fixture("moon-summary.json").toResponseBody("application/json".toMediaType())).build() }
        val c = WikimediaClient(OkHttpClient.Builder().addInterceptor(server).build(), allowed = { seen })
        assertThat(c.summary("en", "Moon")).isNull()
        assertThat(asked).isEmpty()
        seen = true
        assertThat(c.summary("en", "Moon")!!.qid).isEqualTo(405L)
    }

    @Test fun neverOnTheMainThread() {
        val c = WikimediaClient(OkHttpClient(), onMainThread = { true })
        assertThrows(IllegalStateException::class.java) { c.summary("en", "Moon") }
    }

    @Test fun atMostFourRequestsAtOnceToAHost() {
        val now = AtomicInteger()
        val peak = AtomicInteger()
        val slow = Interceptor { chain ->
            val n = now.incrementAndGet()
            peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            Thread.sleep(30)
            now.decrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(404).body("".toResponseBody()).build()
        }
        val c = WikimediaClient(OkHttpClient.Builder().addInterceptor(slow).build())
        (1..12).map { i -> Thread { c.summary("en", "T$i") }.apply { start() } }.forEach { it.join() }
        assertThat(peak.get()).isAtMost(WikimediaClient.PER_HOST)
    }
}
