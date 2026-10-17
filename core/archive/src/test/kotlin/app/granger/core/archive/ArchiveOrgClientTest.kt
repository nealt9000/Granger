package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

private class FakeArchive : Interceptor {
    val bodies = mutableMapOf<String, String>()
    val status = mutableMapOf<String, Int>()
    var failWith: IOException? = null
    val requests = mutableListOf<Request>()

    fun fixture(path: String, resource: String) {
        bodies[path] = javaClass.classLoader!!.getResourceAsStream(resource)!!.readBytes().decodeToString()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        failWith?.let { throw it }
        val path = request.url.encodedPath
        val code = status[path] ?: if (path in bodies) 200 else 404
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("")
            .code(code).body((bodies[path] ?: "").toResponseBody()).build()
    }
}

class ArchiveOrgClientTest {
    private val server = FakeArchive()
    private val client = ArchiveOrgClient(OkHttpClient.Builder().addInterceptor(server).build(), "Granger/1.0 (+test)")

    private fun item(id: String, resource: String): ArchiveItem {
        server.fixture("/metadata/$id", resource)
        return kotlinx.coroutines.runBlocking { client.item(id) }
    }

    @Test fun searchParsesDocsAndSendsTheUserAgent() = runTest {
        server.fixture("/advancedsearch.php", "search-steam-engine.json")
        val page = client.search("mediatype:texts AND (steam engine)", 1, rows = 3)
        assertThat(page.total).isEqualTo(1341)
        assertThat(page.docs).hasSize(3)
        val first = page.docs[0]
        assertThat(first.identifier).isEqualTo("HistoryOfTheFrickCompany")
        assertThat(first.year).isNull()
        assertThat(first.creator).isEmpty()
        assertThat(first.downloads).isEqualTo(17502L)
        assertThat(first.restricted).isFalse()
        val second = page.docs[1]
        assertThat(second.year).isEqualTo(1902)
        assertThat(second.creator).isEqualTo("Haeder, Hermann, 1854-1920, Powles, Henry Handley Pridham")
        assertThat(second.formats).contains("EPUB")
        assertThat(page.docs[2].creator).isEqualTo("Croft, Terrell, 1880-")
        assertThat(page.docs[2].year).isEqualTo(1922)
        val request = server.requests.single()
        assertThat(request.header("User-Agent")).isEqualTo("Granger/1.0 (+test)")
        assertThat(request.url.queryParameter("q")).isEqualTo("mediatype:texts AND (steam engine)")
        assertThat(request.url.queryParameter("output")).isEqualTo("json")
        assertThat(request.url.queryParameterValues("fl[]")).containsAtLeast("identifier", "title", "downloads")
    }

    @Test fun searchSendsSortAndPage() = runTest {
        server.fixture("/advancedsearch.php", "search-steam-engine.json")
        client.search("x", 2)
        val url = server.requests.single().url
        assertThat(url.queryParameter("sort[]")).isEqualTo("downloads desc")
        assertThat(url.queryParameter("page")).isEqualTo("2")
        assertThat(url.queryParameter("rows")).isEqualTo("50")
        client.search("x", 1, sortByDownloads = false)
        assertThat(server.requests.last().url.queryParameter("sort[]")).isNull()
    }

    @Test fun itemParsesFilesAndPrivateFlags() {
        val id = "lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9"
        val item = item(id, "item-lending.json")
        assertThat(item.restricted).isTrue()
        assertThat(item.year).isEqualTo(1923)
        val epub = item.files.single { it.name == "$id.epub" }
        assertThat(epub.isPrivate).isTrue()
        assertThat(epub.sizeBytes).isEqualTo(61654604L)
        assertThat(epub.sha1).isNotEmpty()
        val lcp = item.files.single { it.name == "${id}_lcp.epub" }
        assertThat(lcp.format).isEqualTo("LCP Encrypted EPUB")
        assertThat(lcp.isPrivate).isFalse()
    }

    @Test fun openEpubItemHasAPublicEpub() {
        val item = item("contributionletu0037vari_w0s3", "item-open-epub.json")
        assertThat(item.creator).isEqualTo("various")
        assertThat(item.restricted).isFalse()
        val epub = item.files.single { it.format == "EPUB" }
        assertThat(epub.isPrivate).isFalse()
        assertThat(epub.source).isEqualTo("derivative")
    }

    @Test fun itemJoinsCreatorsAndListsSubjects() {
        val item = item("scientific-american-1881-10-15", "item-scan-only.json")
        assertThat(item.subjects).contains("steam engine")
        assertThat(item.creator).isEmpty()
        assertThat(item.year).isEqualTo(1881)
        assertThat(item.title).isNotEmpty()
    }

    @Test fun emptyMetadataIsNotFound() = runTest {
        server.bodies["/metadata/nope"] = "{}"
        val e = assertThrows(ArchiveError::class.java) { kotlinx.coroutines.runBlocking { client.item("nope") } }
        assertThat(e).hasMessageThat().isEqualTo("Not found on archive.org")
    }

    @Test fun darkItemIsNotFound() = runTest {
        server.bodies["/metadata/dark"] = """{"is_dark":true,"metadata":{"identifier":"dark"},"files":[]}"""
        val e = assertThrows(ArchiveError::class.java) { kotlinx.coroutines.runBlocking { client.item("dark") } }
        assertThat(e).hasMessageThat().isEqualTo("Not found on archive.org")
    }

    @Test fun serverErrorIsAnArchiveError() = runTest {
        server.status["/advancedsearch.php"] = 503
        val e = assertThrows(ArchiveError::class.java) { kotlinx.coroutines.runBlocking { client.search("x", 1) } }
        assertThat(e).hasMessageThat().contains("503")
        server.failWith = IOException("boom")
        val e2 = assertThrows(ArchiveError::class.java) { kotlinx.coroutines.runBlocking { client.search("x", 1) } }
        assertThat(e2).hasMessageThat().isEqualTo("Couldn't reach archive.org")
    }

    @Test fun malformedBodyIsAnArchiveError() = runTest {
        server.bodies["/advancedsearch.php"] = "<html>"
        assertThrows(ArchiveError::class.java) { kotlinx.coroutines.runBlocking { client.search("x", 1) } }
    }

    @Test fun downloadUrlEncodesEachSegment() {
        assertThat(client.downloadUrl("x", "sub dir/My Book.epub")).isEqualTo("https://archive.org/download/x/sub%20dir/My%20Book.epub")
        assertThat(client.detailsUrl("x")).isEqualTo("https://archive.org/details/x")
    }
}
