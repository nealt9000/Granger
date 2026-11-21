package app.granger.core.archive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The iPhone's Ktor GET under the shared client (Plan I5 Task 2): a recorded search parsed, Local only refused. */
class ArchiveIosTest {
    @Test fun aRecordedSearchThroughKtor() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.toString()
            assertEquals("Granger/1.0 (+test)", request.headers["User-Agent"])
            respond(SEARCH_JSON, HttpStatusCode.OK)
        }
        val client = ArchiveOrgClient(KtorArchiveHttp(HttpClient(engine)), "Granger/1.0 (+test)")
        val page = client.search(ArchiveQuery.build("steam engine", null, filtered = true), 1)
        assertEquals(1341, page.total)
        assertEquals(1902, page.docs[1].year)
        assertEquals("1", io.ktor.http.Url(seen.single()).parameters["page"])
        assertEquals("mediatype:texts AND (steam engine) AND year:[* TO 1930]", io.ktor.http.Url(seen.single()).parameters["q"])
    }

    @Test fun librivoxsNoResultsIsAnEmptyPageAndLocalOnlySendsNothing() = runTest {
        val engine = MockEngine { respond("""{"error":"Audiobooks could not be found"}""", HttpStatusCode.NotFound) }
        val librivox = LibriVoxClient(KtorArchiveHttp(HttpClient(engine)), "ua", sleep = {})
        assertEquals(0, librivox.search(LibriVoxQuery.Recent, 0).books.size)
        val gated = ArchiveOrgClient(KtorArchiveHttp(HttpClient(engine), allowed = { false }), "ua")
        assertEquals(ArchiveProblem.UNREACHABLE, assertFailsWith<ArchiveError> { gated.item("x") }.problem)
        assertEquals(0, engine.requestHistory.count { it.url.encodedPath.startsWith("/metadata") })
    }
}
