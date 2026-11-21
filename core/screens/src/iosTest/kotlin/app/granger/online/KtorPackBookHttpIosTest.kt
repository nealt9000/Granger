package app.granger.online

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan I5 Task 7: the online bookshelf's requests on the iPhone, against a fake Kiwix mirror (no network): the HEAD
 * that finds the mirror isn't followed, a range read sends its Range and streams its bytes, the list's GET follows
 * redirects, and nothing is sent while the gate is shut.
 */
class KtorPackBookHttpIosTest {
    private val zim = ByteArray(200) { it.toByte() }
    private val asked = mutableListOf<String>()

    private val engine = MockEngine { request ->
        val url = request.url.toString()
        asked += "${request.method.value} $url ${request.headers[HttpHeaders.Range].orEmpty()}".trim()
        assertEquals("Granger/test", request.headers[HttpHeaders.UserAgent])
        when {
            url == PACK -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/mirror/pack.zim"))
            url == MIRROR && request.method == HttpMethod.Head -> respond("", HttpStatusCode.OK)
            url == MIRROR -> {
                val (a, b) = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split("-").map(String::toInt)
                respond(zim.copyOfRange(a, b + 1), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentLength, "${b - a + 1}"))
            }
            url == LIST -> respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, LIST_AT))
            url == LIST_AT -> respond("the list".encodeToByteArray(), HttpStatusCode.OK)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    private fun http(open: Boolean = true) = KtorPackBookHttp("Granger/test", { open }, HttpClient(engine))

    @Test fun theMirrorIsFoundWithoutFollowingAndARangeIsStreamed() = runBlocking {
        val hop = http().head(PACK)!!
        assertEquals(302, hop.code)
        assertEquals(MIRROR, hop.location)
        assertEquals(200, http().head(MIRROR)!!.code)

        val got = ArrayList<Byte>()
        val answer = http().range(MIRROR, 8, 23, start = { it.code == 206 }) { buf, n -> repeat(n) { got += buf[it] }; true }!!
        assertEquals(206, answer.code)
        assertEquals(16L, answer.contentLength)
        assertContentEquals(zim.copyOfRange(8, 24), got.toByteArray())
        assertTrue("GET $MIRROR bytes=8-23" in asked)

        // A start that says no reads no body.
        var bodies = 0
        http().range(MIRROR, 0, 9, start = { false }) { _, _ -> bodies++; true }
        assertEquals(0, bodies)
    }

    @Test fun theListFollowsRedirectsIntoItsFile() = runBlocking {
        val to = NSTemporaryDirectory() + NSUUID().UUIDString + ".gbooks"
        assertTrue(http().fetch(LIST, to))
        assertContentEquals("the list".encodeToByteArray(), SystemFileSystem.source(Path(to)).buffered().use { it.readByteArray() })
        assertFalse(http().fetch("https://nowhere.example/x", to))
        SystemFileSystem.delete(Path(to), mustExist = false)
    }

    @Test fun nothingIsSentWhileTheGateIsShut() = runBlocking {
        val shut = http(open = false)
        assertNull(shut.head(PACK))
        assertNull(shut.range(MIRROR, 0, 1, start = { true }) { _, _ -> true })
        assertFalse(shut.fetch(LIST, NSTemporaryDirectory() + "never"))
        assertTrue(asked.isEmpty())
    }

    private companion object {
        const val PACK = "https://download.kiwix.org/zim/other/pack.zim"
        const val MIRROR = "https://download.kiwix.org/mirror/pack.zim"
        const val LIST = "https://github.com/x/releases/download/bookpacks/pack.gbooks"
        const val LIST_AT = "https://objects.example/pack.gbooks"
    }
}
