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

    @Test fun yearIsTheFirstFourDigitsOfTheDateElseTheYearField() {
        fun yearFor(meta: String): Int? {
            server.bodies["/metadata/y"] = """{"metadata":{"identifier":"y",$meta},"files":[]}"""
            return kotlinx.coroutines.runBlocking { client.item("y") }.year
        }
        assertThat(yearFor("\"date\":\"1890-05-01\"")).isEqualTo(1890)
        assertThat(yearFor("\"date\":\"c1890\"")).isEqualTo(1890)
        assertThat(yearFor("\"date\":\"[1890]\"")).isEqualTo(1890)
        assertThat(yearFor("\"date\":\"May 12, 1890\"")).isEqualTo(1890)
        assertThat(yearFor("\"date\":\"circa 189-\"")).isNull()
        assertThat(yearFor("\"date\":\"unknown\",\"year\":\"1901\"")).isEqualTo(1901)
        assertThat(yearFor("\"year\":1902")).isEqualTo(1902)
        assertThat(yearFor("\"date\":\"1890\",\"year\":\"1950\"")).isEqualTo(1890)
        assertThat(yearFor("\"title\":\"t\"")).isNull()
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

    @Test fun itemParsesAudioFileFields() {
        val item = item(HOLST, "item-holst-planets.json")
        assertThat(item.mediatype).isEqualTo("audio")
        assertThat(item.collections).containsExactly("hifidelity_potpourri", "hifidelity")
        val mars = item.files.single { it.name.endsWith(".mp3") && "I. Mars" in it.name }
        assertThat(mars.track).isEqualTo(1)
        assertThat(mars.lengthMs).isEqualTo(373_160L)
        assertThat(mars.format).isEqualTo("VBR MP3")
        assertThat(mars.title).isEmpty()
        assertThat(mars.album).isEmpty()
        val filesXml = item.files.single { it.name == "${HOLST}_files.xml" }
        assertThat(filesXml.sha1).isNull()
        assertThat(filesXml.track).isNull()
        assertThat(filesXml.lengthMs).isNull()
    }

    @Test fun lengthParsesSecondsAndClock() {
        val item = item("time_machine_0805_librivox", "item-librivox-time-machine.json")
        assertThat(item.collections).containsExactly("librivoxaudio", "audio_bookspoetry")
        val low = item.files.single { it.name == "timemachinewells_01_ae_64kb.mp3" }
        assertThat(low.lengthMs).isEqualTo(1_172_000L)
        assertThat(low.track).isNull()
        val vbr = item.files.single { it.name == "timemachinewells_01_ae.mp3" }
        assertThat(vbr.track).isEqualTo(1)
        assertThat(vbr.lengthMs).isEqualTo(1_172_320L)
        assertThat(vbr.title).isEqualTo("Chapter 01")
        assertThat(vbr.album).isEqualTo("The Time Machine")
        assertThat(vbr.creator).isEqualTo("H.G. Wells")
        assertThat(item.files.single { it.name == "timemachinewells_06.mp3" }.track).isEqualTo(7)
        assertThat(item.files.single { it.name == "time_machine_0805_librivox_files.xml" }.sha1).isNull()
    }

    @Test fun trackAndLengthEdgeCases() {
        fun file(fields: String): ArchiveFile {
            server.bodies["/metadata/f"] = """{"metadata":{"identifier":"f","collection":"solo"},"files":[{"name":"a.mp3",$fields}]}"""
            val item = kotlinx.coroutines.runBlocking { client.item("f") }
            assertThat(item.collections).containsExactly("solo")
            return item.files.single()
        }
        assertThat(file("\"track\":\"01\"").track).isEqualTo(1)
        assertThat(file("\"track\":\"1/13\"").track).isEqualTo(1)
        assertThat(file("\"track\":\"7\"").track).isEqualTo(7)
        assertThat(file("\"track\":\"A1\"").track).isNull()
        assertThat(file("\"track\":\"\"").track).isNull()
        assertThat(file("\"length\":\"1:02:03\"").lengthMs).isEqualTo(3_723_000L)
        assertThat(file("\"length\":\"19:32\"").lengthMs).isEqualTo(1_172_000L)
        assertThat(file("\"length\":\"373.16\"").lengthMs).isEqualTo(373_160L)
        assertThat(file("\"length\":\"1027\"").lengthMs).isEqualTo(1_027_000L)
        assertThat(file("\"length\":\"abc\"").lengthMs).isNull()
        assertThat(file("\"length\":\"1:2:3:4\"").lengthMs).isNull()
    }

    @Test fun searchAsksForAndParsesTheMediatype() = runTest {
        server.fixture("/advancedsearch.php", "search-audio-music.json")
        val page = client.search(ArchiveQuery.build("", ArchiveShelf.MUSIC_MOST_DOWNLOADED, true, ArchiveKind.MUSIC), 1, rows = 3)
        assertThat(page.total).isEqualTo(52107)
        assertThat(page.docs.map { it.mediatype }).containsExactly("audio", "audio", "audio")
        assertThat(page.docs[0].year).isEqualTo(1904)
        assertThat(server.requests.single().url.queryParameterValues("fl[]")).contains("mediatype")
    }
}
