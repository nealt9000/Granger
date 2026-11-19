package app.granger.online

import app.granger.core.catalog.RecordsEntry
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * Plan O6 Task 4: records read by range from a fake GitHub release (the fixture tools/topicrecords builds: 300 articles,
 * three blocks; Q10 a broad topic). The release address redirects to a "CDN" one, as GitHub's does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteRecordsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(ext: String) = javaClass.classLoader!!.getResourceAsStream("records/wikipedia_xx_test_2026-11.$ext")!!.readBytes()
    private val grec = bytes("grec")
    private val grdir = bytes("grdir")
    private val dirSha = MessageDigest.getInstance("SHA-256").digest(grdir).joinToString("") { "%02x".format(it) }

    private val release = "https://github.com/x/releases/download/records-1/"
    private val entry = RecordsEntry(release + "t.grec", release + "t.grdir", dirSha, grdir.size.toLong(), grec.size.toLong(), 2)

    private val asked = mutableListOf<String>()
    /** How the fake CDN answers a range: by default the bytes asked for. */
    private var cdn: (Int, LongRange) -> Pair<Int, ByteArray> = { _, r -> 206 to grec.copyOfRange(r.first.toInt(), r.last.toInt() + 1) }
    private var cdnCalls = 0
    private var resolves = 0

    private fun response(chain: Interceptor.Chain, code: Int, body: ByteArray, location: String? = null) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(code)
            .apply { location?.let { header("Location", it) } }
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()

    private val server = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        asked += "${req.method} $url"
        when {
            url == entry.dirUrl -> response(chain, 200, grdir)
            url == entry.url && req.method == "HEAD" -> { resolves++; response(chain, 302, ByteArray(0), "https://cdn.example/t.grec?sig=$resolves") }
            url.startsWith("https://cdn.example/t.grec") -> {
                val (a, b) = req.header("Range")!!.removePrefix("bytes=").split("-").map(String::toLong)
                val (code, body) = cdn(cdnCalls++, a..b)
                response(chain, code, body)
            }
            else -> response(chain, 404, ByteArray(0))
        }
    }

    private var online = true
    private val saved by lazy { SavedStore(tmp.newFolder("saved")) }
    private fun records(e: RecordsEntry? = entry) = RemoteRecords(
        OkHttpClient.Builder().addInterceptor(server).build(), { lang -> e.takeIf { lang == "xx" } }, tmp.root.resolve("records"), saved, { online },
    )

    @Test fun aRecordIsOneRangeReadAfterTheDirectoryAndThenComesFromSavedForNow() {
        val r = records()
        val q10 = r.record("xx", 10, "wikipedia_xx_online")!!
        assertThat(q10.parts!!.kinds.map { it.qid }).containsExactly(20L)
        assertThat(asked.count { it.startsWith("GET https://cdn.example") }).isEqualTo(1)
        assertThat(resolves).isEqualTo(1)
        // Same block: no new request, and with no signal it still reads (Local only included).
        online = false
        assertThat(r.record("xx", 20, "wikipedia_xx_online")!!.holders.map { it.qid }).containsExactly(30L, 10L).inOrder()
        assertThat(asked.count { it.startsWith("GET https://cdn.example") }).isEqualTo(1)
        // A fresh reader (the app restarted) finds the directory and the block kept.
        assertThat(records().record("xx", 20, "wikipedia_xx_online")).isNotNull()
    }

    @Test fun offlineWithNothingKeptAsksNothing() {
        online = false
        assertThat(records().record("xx", 10, "wikipedia_xx_online")).isNull()
        assertThat(asked).isEmpty()
    }

    @Test fun anExpiredSignedAddressIsResolvedOnceMore() {
        cdn = { call, r -> if (call == 0) 403 to ByteArray(0) else 206 to grec.copyOfRange(r.first.toInt(), r.last.toInt() + 1) }
        val r = records()
        // The directory read resolved nothing yet; the first block read resolves, gets 403, resolves again.
        assertThat(r.record("xx", 150, "wikipedia_xx_online")!!.path).isEqualTo("A150")
        assertThat(resolves).isEqualTo(2)
    }

    @Test fun aBadBlockIsReadOnceMoreThenGivenUp() {
        cdn = { call, r ->
            val good = grec.copyOfRange(r.first.toInt(), r.last.toInt() + 1)
            206 to if (call == 0) good.also { it[30] = (it[30].toInt() xor 0x55).toByte() } else good
        }
        assertThat(records().record("xx", 5, "wikipedia_xx_online")!!.path).isEqualTo("A5")
        cdn = { _, r -> 206 to grec.copyOfRange(r.first.toInt(), r.last.toInt() + 1).also { it[30] = (it[30].toInt() xor 0x55).toByte() } }
        assertThat(records().record("xx", 290, "wikipedia_xx_online")).isNull()
    }

    @Test fun aServerThatIgnoresTheRangeIsNotRead() {
        cdn = { _, _ -> 200 to grec }
        assertThat(records().record("xx", 5, "wikipedia_xx_online")).isNull()
    }

    @Test fun aDirectoryThatDoesntMatchItsChecksumIsRefused() {
        assertThat(records(entry.copy(dirSha256 = "0".repeat(64))).record("xx", 5, "wikipedia_xx_online")).isNull()
        assertThat(asked.none { it.contains("cdn.example") }).isTrue()
    }

    @Test fun aLanguageWithoutRecordsIsNull() {
        assertThat(records().record("de", 5, "wikipedia_de_online")).isNull()
        assertThat(asked).isEmpty()
    }
}
