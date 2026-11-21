package app.granger.core.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared book rules on a string, on both phones (Plan I5 Task 3): the JVM host tests and the Simulator run it. */
class BookRulesTest {
    @Test
    fun windowsPackParagraphsIntoSearchSizedChunks() {
        val para = "word ".repeat(100).trim()
        val texts = windows(sequenceOf(para, "", para, para))
        assertTrue(texts.all { it.length <= MAX_CHUNK })
        assertTrue(texts.dropLast(1).all { it.length >= MIN_CHUNK })
        val chunks = chunked("c001.xhtml", texts)
        assertEquals(0.0, chunks.first().progression)
        assertEquals((0 until texts.size).toList(), chunks.map { it.ordinal })
    }

    @Test
    fun snippetsFindThePhraseAndHighlightTerms() {
        val text = "Boil the water for one minute before drinking. Water from a spring may be safe."
        val quote = assertNotNull(Snippets.quote(text, "the  WATER"))
        assertEquals("the water", quote.highlight)
        assertEquals("<b>Water</b>", Snippets.around("A & b. Water.", "water").substringAfter("b. ").substringBefore("."))
        assertNull(Snippets.quote(text, "nothing"))
    }

    @Test
    fun aTextNamesItsOwnTitle() {
        assertEquals("Walden", TextToEpub.titleOf("Title: Walden\n\nAuthor: Thoreau", markdown = false))
        assertEquals("Field notes", TextToEpub.titleOf("# Field notes\n\nSome text.", markdown = true))
        assertNull(TextToEpub.titleOf("Chapter 1\n\nIt was.", markdown = false))
    }

    @Test
    fun xmlCleaningAndEscaping() {
        assertEquals("a&lt;b&gt; &amp; &quot;c&quot;", esc("a<b> & \"c\""))
        assertEquals("ab\tc", stripInvalidXml("a\u0001b\tc￿"))
        assertEquals("héllo", xmlText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".encodeToByteArray()))
    }

    @Test
    fun textDecodingFallsBackToWindows1252() {
        assertEquals("café", TextDecoding.decode("café".encodeToByteArray()))
        assertEquals("café “quoted”", TextDecoding.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x93.toByte()) + "quoted".encodeToByteArray() + byteArrayOf(0x94.toByte())))
    }

    @Test
    fun theQualityGateAndOriginRules() {
        assertTrue(PdfToEpub.runTogether("thesewordsareallruntogetherintoone and some short ones") > 0.1f)
        assertEquals(0f, PdfToEpub.runTogether("see https://example.org/a/very/long/address/indeed here"))
        val page = PdfOrigin.Companion.PageLook(visible = 500, hidden = 0, glyphless = 0, pageImage = false)
        assertEquals(PdfOrigin.BORN_DIGITAL, PdfOrigin.classify(List(4) { page }))
        assertEquals(PdfOrigin.SCAN, PdfOrigin.classify(List(4) { page.copy(visible = 0, hidden = 500, pageImage = true) }))
        assertTrue(ComicToPdf.isPicture("Issue 1/p01.JPG"))
        assertTrue(!ComicToPdf.isPicture("__MACOSX/p01.jpg"))
        assertEquals(42, PdfText.pageOf(PdfText.href(42)))
    }
}
