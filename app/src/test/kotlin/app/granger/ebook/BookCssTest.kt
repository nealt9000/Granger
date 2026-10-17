package app.granger.ebook

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookCssTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Archive.org's scan EPUBs: served as text/html, `<p/>` opens a paragraph the cover image then sits in. */
    private val scanPage = """
        <?xml version='1.0' encoding='utf-8'?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page 0</title>
        <link href="style/style.css" rel="stylesheet" type="text/css"/></head>
        <body><p/><img src="image_0000_00.jpeg" alt="Image 0"/></body></html>
    """.trimIndent()

    @Test fun theStyleGoesLastInTheHeadSoItFollowsTheBooksOwnCss() {
        val out = String(BookCss.inject(scanPage.toByteArray()))
        val style = out.indexOf(BookCss.STYLE)
        assertThat(style).isGreaterThan(out.indexOf("style/style.css"))
        assertThat(out.indexOf("</head>")).isEqualTo(style + BookCss.STYLE.length)
        assertThat(out.replace(BookCss.STYLE, "")).isEqualTo(scanPage)
    }

    @Test fun aParagraphHoldingAnImageLosesItsIndent() {
        // The book's `p { text-indent: 4em }` pushed a 100%-wide image 4em past the right edge.
        assertThat(BookCss.STYLE).contains("p:has(img) { text-indent: 0 !important; }")
    }

    @Test fun theBooksOwnImageSizesAreLeftAlone() {
        // Equation glyphs at `height: 1.2em` and `height: 100%` covers keep their sizes; ReadiumCSS already caps width.
        assertThat(BookCss.STYLE).doesNotContain("height")
        assertThat(BookCss.STYLE).doesNotContain("width")
    }

    @Test fun injectingTwiceAddsTheStyleOnce() {
        val once = BookCss.inject(scanPage.toByteArray())
        assertThat(String(BookCss.inject(once))).isEqualTo(String(once))
    }

    @Test fun aStyleAttributeCountsAsStylesOnlyAsReadiumCountsIt() {
        // Readium's hasStyles looks for the literal " style="; a newline before it doesn't count there, so not here.
        val newline = "<html><head></head><body\nstyle=\"x\"></body></html>"
        assertThat(String(BookCss.inject(newline.toByteArray()))).isEqualTo(newline)
        val space = "<html><head></head><body style=\"x\"></body></html>"
        assertThat(String(BookCss.inject(space.toByteArray()))).contains(BookCss.STYLE)
    }

    @Test fun anUnstyledPageIsLeftAloneSoReadiumStillGivesItItsDefaultStyles() {
        val plain = "<html><head><title>x</title></head><body><p>Hi</p></body></html>"
        assertThat(String(BookCss.inject(plain.toByteArray()))).isEqualTo(plain)
    }

    @Test fun anUpperCaseHeadAndNonAsciiTextSurvive() {
        val page = "<HTML><HEAD><style>p{}</style></HEAD><BODY>naïve — “quoted”</BODY></HTML>"
        val out = String(BookCss.inject(page.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        assertThat(out).isEqualTo(page.replace("</HEAD>", BookCss.STYLE + "</HEAD>"))
    }

    @Test fun aPageWithNoHeadIsLeftAlone() {
        val page = "<html><body style=\"x\"><img src=\"a.jpg\"/></body></html>"
        assertThat(String(BookCss.inject(page.toByteArray()))).isEqualTo(page)
    }

    @Test fun theOpenerServesHtmlWithTheStyleAndLeavesCssAlone() = runTest {
        val opener = ReadiumBookOpener(ApplicationProvider.getApplicationContext())
        val publication = opener.open(scanEpub())!!
        try {
            val page = publication.readingOrder.single()
            val html = String(publication.get(page)!!.read().getOrNull()!!)
            assertThat(html.split(BookCss.STYLE).size - 1).isEqualTo(1)
            val css = publication.resources.single { it.href.toString().endsWith(".css") }
            assertThat(String(publication.get(css)!!.read().getOrNull()!!)).doesNotContain(BookCss.STYLE)
        } finally {
            publication.close()
        }
    }

    private fun scanEpub(): File {
        val file = tmp.newFile("scan.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()) }
            put("META-INF/container.xml", """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("EPUB/content.opf", """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">scan</dc:identifier>
                <dc:title>Scan</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2024-01-01T00:00:00Z</meta></metadata>
                <manifest><item href="style/style.css" id="css" media-type="text/css"/>
                <item href="page_0.html" id="p0" media-type="text/html"/></manifest>
                <spine><itemref idref="p0"/></spine></package>""")
            put("EPUB/style/style.css", "img { max-width: 100%; } p { text-indent: 4em; }")
            put("EPUB/page_0.html", scanPage)
        }
        return file
    }
}
