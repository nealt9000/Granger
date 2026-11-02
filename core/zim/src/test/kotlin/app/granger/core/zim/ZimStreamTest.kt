package app.granger.core.zim

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Large stored entries are streamed from the `.zim` file, never read whole (clean-up list, from Plan 2). */
class ZimStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private val big = ZimResolver.STREAM_MIN_BYTES.toInt() + 1_000
    /** A stand-in `.zim`: 100 bytes of something else, then the entry's bytes (i % 251), then a tail. */
    private val zim: File by lazy {
        tmp.newFile("pack.zim").apply { writeBytes(ByteArray(100) { 7 } + ByteArray(big) { (it % 251).toByte() } + ByteArray(50) { 9 }) }
    }
    private val expected = ByteArray(big) { (it % 251).toByte() }

    private fun resolver(stored: Map<String, ZimRange>) = ZimResolver(archiveFor = {
        FakeZimArchive(
            entries = mapOf(
                "talk.webm" to ("video/webm" to "read whole: wrong"),
                "small.png" to ("image/png" to "PNG"),
                "big.html" to ("text/html" to "<h1>Big</h1>"),
            ),
            redirects = mapOf("Talk" to "talk.webm"),
            stored = stored,
        )
    })

    private val streaming by lazy { streamingResolver() }
    private fun streamingResolver() = resolver(mapOf(
        "talk.webm" to ZimRange("talk.webm", "video/webm", zim, 100L, big.toLong()),
        "small.png" to ZimRange("small.png", "image/png", zim, 100L, 3L),
        "big.html" to ZimRange("big.html", "text/html", zim, 100L, big.toLong()),
    ))

    private fun stream(path: String, range: String? = null) =
        streaming.response(ZimUrls.forEntry("p", path), range) as ZimResponse.Stream

    @Test fun aLargeStoredEntryIsStreamedFromTheFile() {
        val r = stream("Talk")   // through the redirect
        assertThat(r.status).isEqualTo(200)
        assertThat(r.mimeType).isEqualTo("video/webm")
        assertThat(r.headers).containsEntry("Content-Length", "$big")
        assertThat(r.headers).containsEntry("Accept-Ranges", "bytes")
        assertThat(r.open().use { it.readBytes() }).isEqualTo(expected)
    }

    @Test fun smallEntriesAndPagesAreStillReadWhole() {
        assertThat(String((streaming.response(ZimUrls.forEntry("p", "small.png")) as ZimResponse.Content).bytes)).isEqualTo("PNG")
        // HTML is patched by the reader, so it never streams, however big.
        assertThat(String((streaming.response(ZimUrls.forEntry("p", "big.html")) as ZimResponse.Content).bytes)).isEqualTo("<h1>Big</h1>")
        // A compressed entry (no stored range) is read as before.
        val compressed = resolver(emptyMap()).response(ZimUrls.forEntry("p", "talk.webm")) as ZimResponse.Content
        assertThat(String(compressed.bytes)).isEqualTo("read whole: wrong")
    }

    @Test fun aRangeIsAnsweredWithPartialContent() {
        val r = stream("talk.webm", "bytes=1000-1999")
        assertThat(r.status).isEqualTo(206)
        assertThat(r.headers).containsEntry("Content-Range", "bytes 1000-1999/$big")
        assertThat(r.headers).containsEntry("Content-Length", "1000")
        assertThat(r.open().use { it.readBytes() }).isEqualTo(expected.copyOfRange(1000, 2000))
    }

    @Test fun openEndedAndSuffixRanges() {
        val tail = stream("talk.webm", "bytes=${big - 10}-")
        assertThat(tail.status).isEqualTo(206)
        assertThat(tail.open().use { it.readBytes() }).isEqualTo(expected.copyOfRange(big - 10, big))
        val last = stream("talk.webm", "bytes=-5")
        assertThat(last.headers).containsEntry("Content-Range", "bytes ${big - 5}-${big - 1}/$big")
        assertThat(last.open().use { it.readBytes() }).isEqualTo(expected.copyOfRange(big - 5, big))
        // An end past the entry is cut to it; the stream never reads into the file's tail.
        val over = stream("talk.webm", "bytes=${big - 3}-${big + 500}")
        assertThat(over.open().use { it.readBytes() }).isEqualTo(expected.copyOfRange(big - 3, big))
    }

    @Test fun aRangePastTheEndIsUnsatisfiable() {
        val r = stream("talk.webm", "bytes=$big-")
        assertThat(r.status).isEqualTo(416)
        assertThat(r.headers).containsEntry("Content-Range", "bytes */$big")
        assertThat(r.open().use { it.readBytes() }).isEmpty()
    }

    @Test fun rangesThisDoesntTakeGetTheWholeEntry() {
        for (h in listOf("bytes=0-10,20-30", "items=0-10", "bytes=abc", "bytes=10-5", "bytes=-0")) {
            assertThat(stream("talk.webm", h).status).isEqualTo(200)
        }
    }
}
