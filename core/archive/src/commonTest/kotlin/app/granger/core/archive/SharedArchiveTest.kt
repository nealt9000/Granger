package app.granger.core.archive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A recorded archive.org search, cut down to two docs (search-steam-engine.json's first and second). */
internal const val SEARCH_JSON = """{"responseHeader":{"status":0},"response":{"numFound":1341,"start":0,"docs":[
{"identifier":"HistoryOfTheFrickCompany","title":"History of the Frick Company","format":["Image Container PDF","Text PDF"],"downloads":17502},
{"identifier":"steamengine00haed","title":"Steam engines","creator":["Haeder, Hermann, 1854-1920","Powles, Henry Handley Pridham"],"year":"1902","format":["DjVuTXT","Text PDF"],"downloads":"812","access-restricted-item":"false"}]}}"""

/** The shared clients' rules, run on both phones (Plan I5 Task 2). */
class SharedArchiveTest {
    private class Recorder(private val reply: (String) -> HttpReply) : ArchiveHttp {
        val urls = mutableListOf<String>()
        val agents = mutableListOf<String>()
        override suspend fun get(url: String, userAgent: String): HttpReply {
            urls += url
            agents += userAgent
            return reply(url)
        }
    }

    @Test fun searchParsesARecordedPageAndSendsOkHttpsQueryString() = runTest {
        val http = Recorder { HttpReply(200, SEARCH_JSON) }
        val page = ArchiveOrgClient(http, "Granger/1.0 (+test)").search("mediatype:texts AND (steam engine)", 1, rows = 3)
        assertEquals(1341, page.total)
        assertEquals(listOf("HistoryOfTheFrickCompany", "steamengine00haed"), page.docs.map { it.identifier })
        assertNull(page.docs[0].year)
        assertEquals(1902, page.docs[1].year)
        assertEquals(812L, page.docs[1].downloads)
        assertEquals("Haeder, Hermann, 1854-1920, Powles, Henry Handley Pridham", page.docs[1].creator)
        assertEquals("Granger/1.0 (+test)", http.agents.single())
        // As OkHttp's addQueryParameter wrote it: spaces %20, brackets and parentheses encoded.
        assertEquals(
            "https://archive.org/advancedsearch.php?q=mediatype%3Atexts%20AND%20%28steam%20engine%29" +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year&fl%5B%5D=format" +
                "&fl%5B%5D=access-restricted-item&fl%5B%5D=downloads&fl%5B%5D=mediatype" +
                "&sort%5B%5D=downloads%20desc&rows=3&page=1&output=json",
            http.urls.single(),
        )
    }

    @Test fun noAnswerIsUnreachableAndAStatusIsAnswered() = runTest {
        val down = ArchiveOrgClient({ _, _ -> throw Unreachable("offline") }, "ua")
        assertEquals(ArchiveProblem.UNREACHABLE, assertFailsWith<ArchiveError> { down.search("x", 1) }.problem)
        val busy = ArchiveOrgClient({ _, _ -> HttpReply(503, "") }, "ua")
        val e = assertFailsWith<ArchiveError> { busy.search("x", 1) }
        assertEquals(ArchiveProblem.ANSWERED, e.problem)
        assertEquals(listOf<Any>(503), e.textArgs)
    }

    @Test fun theBookRuleIs1930AndTheRecordingRuleRolls() {
        assertEquals("year:[* TO 1930]", ArchiveQuery.PUBLIC_DOMAIN)
        assertEquals(ArchiveQuery.PUBLIC_DOMAIN, PublicDomain.clause(ArchiveKind.TEXTS, 2040))
        assertEquals("year:[1860 TO 1925]", PublicDomain.clause(ArchiveKind.MUSIC, 2026))
        assertTrue(ArchiveQuery.build("steam", null, filtered = true).endsWith(" AND year:[* TO 1930]"))
        val item = ArchiveItem("x", "T", "", "", 1925, emptyList(), "", "", "", false, emptyList())
        assertTrue(ArchiveOffers.isPublicDomainRecording(item, 2026))
        assertTrue(!ArchiveOffers.isPublicDomainRecording(item.copy(year = 1926), 2026))
    }

    @Test fun queryComponentsEncodeAsOkHttpDoes() {
        assertEquals("%7Bid%2Ctitle%7D", encodeQueryComponent("{id,title}"))
        assertEquals("a%2Bb%20c%25", encodeQueryComponent("a+b c%"))
        assertEquals("poes%C3%ADa*-._%7E", encodeQueryComponent("poesía*-._~"))
    }

    @Test fun urlsParseAsOkHttpDoes() {
        val u = WebUrl.parse("https://www.Archive.org/details/time_machine_1012_librivox")!!
        assertEquals("www.archive.org", u.host)
        assertEquals(listOf("details", "time_machine_1012_librivox"), u.pathSegments)
        assertEquals(listOf("a", "b", ""), WebUrl.parse("http://x/a/b/")!!.pathSegments)
        assertEquals(listOf(""), WebUrl.parse("http://x")!!.pathSegments)
        assertEquals(listOf("a b.mp3"), WebUrl.parse("http://x/a%20b.mp3")!!.pathSegments)
        assertNull(WebUrl.parse("ftp://x/a"))
        assertNull(WebUrl.parse("not a url"))
        assertEquals("time_machine_1012_librivox", LibriVoxClient.archiveIdentifierOf("https://archive.org/details/time_machine_1012_librivox"))
        assertNull(LibriVoxClient.archiveIdentifierOf("https://example.org/details/x"))
    }

    @Test fun secondsBecomeMillisecondsHalfUp() {
        assertEquals(373_160L, millisOf("373.16"))
        assertEquals(1L, millisOf("0.0005"))
        assertEquals(0L, millisOf("0.0004"))
        assertEquals(500L, millisOf(".5"))
        assertEquals(100_000L, millisOf("1e2"))
        assertNull(millisOf("-1"))
        assertNull(millisOf("."))
        assertNull(millisOf("abc"))
    }
}
