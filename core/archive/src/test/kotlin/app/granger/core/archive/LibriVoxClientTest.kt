package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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

/** Serves the Task 1 LibriVox fixtures by query parameter, and records when each request was sent on [clock]. */
private class FakeLibriVox(private val clock: () -> Long) : Interceptor {
    val requests = mutableListOf<Request>()
    val sentAt = mutableListOf<Long>()
    var titleBody = resource("librivox-search-time-machine.json")
    var authorBody = """{"books":[]}"""
    var status: Int? = null
    var statusBody = ""
    var failWith: IOException? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        sentAt += clock()
        failWith?.let { throw it }
        val url = request.url
        val (code, body) = status?.let { it to statusBody } ?: when {
            url.queryParameter("id") == "817" -> 200 to resource("librivox-book-817.json")
            url.queryParameter("id") != null -> 404 to resource("librivox-not-found.json")
            url.queryParameter("sort_field") != null -> 200 to resource("librivox-recent.json")
            url.queryParameter("title") != null -> 200 to titleBody
            url.queryParameter("author") != null -> 200 to authorBody
            url.queryParameter("genre") != null -> 200 to resource("librivox-recent.json")
            else -> 404 to resource("librivox-not-found.json")
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("")
            .code(code).body(body.toResponseBody()).build()
    }

    companion object {
        fun resource(name: String): String =
            FakeLibriVox::class.java.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()
    }
}

class LibriVoxClientTest {
    private var now = 50_000L
    private val sleeps = mutableListOf<Long>()
    private val server = FakeLibriVox { now }
    private val client = LibriVoxClient(
        OkHttpClient.Builder().addInterceptor(server).build(), "Granger/1.0 (+test)",
        clock = { now }, sleep = { ms -> sleeps += ms; now += ms },
    )

    @Test fun searchParsesBooksAndSendsTheUserAgent() = runTest {
        val page = client.search(LibriVoxQuery.Genre("Science Fiction"), offset = 0)
        assertThat(page.books.map { it.id }).containsExactly("23008", "23007", "23006").inOrder()

        val search = client.search(LibriVoxQuery.Text("Time Machine"), offset = 0, limit = 5)
        val first = search.books.first()
        assertThat(first.id).isEqualTo("817")
        assertThat(first.title).isEqualTo("Time Machine")
        assertThat(first.authors).containsExactly("H. G. Wells")
        assertThat(first.sections).isEmpty()
        assertThat(first.genres).isEmpty()
        assertThat(first.totalTimeMs).isEqualTo(14_969_000L)
        assertThat(search.hasMore).isTrue()
        for (request in server.requests) {
            assertThat(request.header("User-Agent")).isEqualTo("Granger/1.0 (+test)")
            assertThat(request.url.encodedPath).isEqualTo("/api/feed/audiobooks/")
            assertThat(request.url.queryParameter("format")).isEqualTo("json")
            assertThat(request.url.queryParameter("extended")).isNull()
            assertThat(request.url.queryParameter("fields"))
                .isEqualTo("{id,title,authors,language,totaltime,totaltimesecs,num_sections}")
        }
        assertThat(server.requests.first().url.queryParameter("genre")).isEqualTo("Science Fiction")
        assertThat(server.requests.first().url.queryParameter("limit")).isEqualTo("50")
        assertThat(server.requests.first().url.queryParameter("offset")).isEqualTo("0")
    }

    @Test fun bookParsesSectionsReadersAndArchiveId() = runTest {
        val book = client.book("817")
        val request = server.requests.single().url
        assertThat(request.queryParameter("id")).isEqualTo("817")
        assertThat(request.queryParameter("extended")).isEqualTo("1")
        assertThat(request.queryParameter("coverart")).isEqualTo("1")
        assertThat(book.title).isEqualTo("Time Machine")
        assertThat(book.sections).hasSize(13)
        val first = book.sections[0]
        assertThat(first.id).isEqualTo("133434")
        assertThat(first.number).isEqualTo(0)
        assertThat(first.title).isEqualTo("Chapter 1")
        assertThat(first.readers).containsExactly("Aaron Elliott")
        assertThat(first.playtimeMs).isEqualTo(1_172_000L)
        assertThat(first.listenUrl).endsWith("/timemachinewells_01_ae_64kb.mp3")
        assertThat(book.archiveIdentifier).isEqualTo("time_machine_0805_librivox")
        assertThat(book.totalTimeMs).isEqualTo(14_969_000L)
        assertThat(book.genres).containsExactly("Science Fiction")
        assertThat(book.language).isEqualTo("English")
        assertThat(book.copyrightYear).isEqualTo("1895")
        assertThat(book.pageUrl).isEqualTo("https://librivox.org/the-time-machine-by-hg-wells/")
        assertThat(book.description).startsWith("The Time Machine is a novel by H. G. Wells")
        assertThat(book.description).doesNotContain("<")
    }

    @Test fun brandNewBookWithoutAnArchiveItemHasNoIdentifier() = runTest {
        server.status = 200
        server.statusBody = """{"books":[{"id":"23008","title":"Spot","url_iarchive":"","totaltime":"","totaltimesecs":0,
            "authors":[{"first_name":"Thomas","last_name":"Hardy"}],"sections":[]}]}"""
        val book = client.book("23008")
        assertThat(book.archiveIdentifier).isNull()
        assertThat(book.totalTimeMs).isEqualTo(0L)
        assertThat(book.authors).containsExactly("Thomas Hardy")
    }

    @Test fun notFoundIsAnEmptyPage() = runTest {
        server.status = 404
        server.statusBody = FakeLibriVox.resource("librivox-not-found.json")
        val page = client.search(LibriVoxQuery.Genre("Poetry"), offset = 0)
        assertThat(page.books).isEmpty()
        assertThat(page.hasMore).isFalse()
        val text = client.search(LibriVoxQuery.Text("zzzqqq"), offset = 0)
        assertThat(text.books).isEmpty()
        assertThat(text.hasMore).isFalse()
    }

    @Test fun rateLimitIsALibriVoxError() {
        server.status = 429
        val e = assertThrows(LibriVoxError::class.java) { runBlocking { client.search(LibriVoxQuery.Recent, offset = 0) } }
        assertThat(e).hasMessageThat().startsWith("LibriVox is busy")
        assertThat(e).hasMessageThat().isEqualTo("LibriVox is busy right now. Try again in a minute.")
    }

    @Test fun otherFailuresAreLibriVoxErrors() {
        server.status = 500 // an unknown genre: HTTP 500, empty body
        val e = assertThrows(LibriVoxError::class.java) { runBlocking { client.search(LibriVoxQuery.Genre("Nope"), offset = 0) } }
        assertThat(e).hasMessageThat().isEqualTo("LibriVox answered 500")
        server.status = 404
        server.statusBody = "<html>gone</html>"
        val e404 = assertThrows(LibriVoxError::class.java) { runBlocking { client.search(LibriVoxQuery.Recent, offset = 0) } }
        assertThat(e404).hasMessageThat().isEqualTo("LibriVox answered 404")
        server.status = 200
        server.statusBody = "<html>"
        assertThrows(LibriVoxError::class.java) { runBlocking { client.search(LibriVoxQuery.Recent, offset = 0) } }
        server.status = null
        server.failWith = IOException("boom")
        val io = assertThrows(LibriVoxError::class.java) { runBlocking { client.search(LibriVoxQuery.Recent, offset = 0) } }
        assertThat(io).hasMessageThat().isEqualTo("Couldn't reach LibriVox")
    }

    @Test fun anUnknownBookIsNotFound() {
        val e = assertThrows(LibriVoxError::class.java) { runBlocking { client.book("999999") } }
        assertThat(e).hasMessageThat().isEqualTo("Not found on LibriVox")
    }

    @Test fun requestsArePacedOneSecondApart() = runTest {
        client.search(LibriVoxQuery.Recent, offset = 0)
        now += 300
        client.search(LibriVoxQuery.Recent, offset = 50)
        assertThat(server.sentAt).hasSize(2)
        assertThat(server.sentAt[1] - server.sentAt[0]).isAtLeast(1_000L)
        assertThat(sleeps).containsExactly(700L)
        // A request long after the last one isn't held back.
        now += 5_000
        client.search(LibriVoxQuery.Recent, offset = 100)
        assertThat(sleeps).containsExactly(700L)
        // The two requests of a text search are paced too.
        client.search(LibriVoxQuery.Text("time machine"), offset = 0)
        assertThat(server.sentAt[4] - server.sentAt[3]).isAtLeast(1_000L)
    }

    @Test fun recentShelfAsksNewestFirst() = runTest {
        assertThat(LibriVoxShelf.RECENT.query).isEqualTo(LibriVoxQuery.Recent)
        client.search(LibriVoxShelf.RECENT.query, offset = 0)
        val url = server.requests.single().url
        assertThat(url.queryParameter("sort_field")).isEqualTo("id")
        assertThat(url.queryParameter("sort_order")).isEqualTo("desc")
        assertThat(url.queryParameter("genre")).isNull()
    }

    @Test fun shelvesAreRecentlyAddedAndSixGenres() {
        assertThat(LibriVoxShelf.entries.map { it.title }).containsExactly(
            "Recently added", "Fiction", "Poetry", "Children's", "Science fiction", "Mystery & detective", "Philosophy",
        ).inOrder()
        assertThat(LibriVoxShelf.entries.drop(1).map { (it.query as LibriVoxQuery.Genre).name }).containsExactly(
            "General Fiction", "Poetry", "Children's Fiction", "Science Fiction", "Detective Fiction", "Philosophy",
        ).inOrder()
    }

    @Test fun textSearchAsksByTitleAndAuthorAndMerges() = runTest {
        server.authorBody = """{"books":[
            {"id":"5040","title":"Time Machine (Version 4)","authors":[{"first_name":"H. G.","last_name":"Wells"}]},
            {"id":"999","title":"War of the Worlds","authors":[{"first_name":"H. G.","last_name":"Wells"}]}]}"""
        val page = client.search(LibriVoxQuery.Text("The Time Machine Wells"), offset = 10, limit = 50)
        assertThat(server.requests).hasSize(2)
        val title = server.requests.single { it.url.queryParameter("title") != null }.url
        val author = server.requests.single { it.url.queryParameter("author") != null }.url
        // LibriVox titles drop a leading article, so the search does too.
        assertThat(title.queryParameter("title")).isEqualTo("Time Machine Wells")
        assertThat(author.queryParameter("author")).isEqualTo("Wells")
        assertThat(title.queryParameter("offset")).isEqualTo("10")
        assertThat(author.queryParameter("offset")).isEqualTo("10")
        assertThat(page.books.map { it.id }).containsExactly("817", "5040", "5618", "5630", "13949", "999").inOrder()
        assertThat(page.hasMore).isFalse()
    }

    @Test fun idsAreValidatedBeforeAnyRequest() {
        val e = assertThrows(LibriVoxError::class.java) { runBlocking { client.book("../x") } }
        assertThat(e).hasMessageThat().isEqualTo("Not found on LibriVox")
        assertThrows(LibriVoxError::class.java) { runBlocking { client.book("") } }
        assertThat(server.requests).isEmpty()
        assertThat(LibriVoxClient.isValidId("817")).isTrue()
        assertThat(LibriVoxClient.isValidId("8a")).isFalse()
        assertThat(LibriVoxClient.isValidId("")).isFalse()
    }
}
