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
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.shared.publication.Layout
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Picture books page by page (Plan KB walkthrough): Prunelle's Kotobee EPUBs say "reflowable" for the book, but every
 * page is one picture in a sized viewport, so the opener lays them out fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FixedPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A page as Prunelle's A Toy War has it: a 2000×1000 viewport, one picture, an empty text layer. */
    private fun picturePage(n: Int, text: String = "&#160;") = """
        <!--?xml version='1.0' encoding='UTF-8'?--><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head>
        <link rel="stylesheet" type="text/css" href="pdf.css" />
        <script type="text/javascript">var kotobee = "a long script that is not text";</script>
        <title>Page $n</title>
        <meta name="viewport" content="width=2000, height=1000" />
        </head><body><div id="fxlChapter" style="width:2000px;height:1000px"><div id="layerBg">
        <img height="1000" src="page$n.png" width="2000" /></div><div class="pc">$text</div></div></body></html>
    """.trimIndent()

    private val chapter = """
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" /></head>
        <body><p>It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.</p></body></html>
    """.trimIndent()

    @Test fun aSizedViewportIsAPageSize() {
        assertThat(FixedPages.hasPageSize(picturePage(1))).isTrue()
        assertThat(FixedPages.hasPageSize("""<meta content="height=1000,width=2000" name='viewport'>""")).isTrue()
        // A reflowable page's viewport follows the device; no viewport at all is reflowable too.
        assertThat(FixedPages.hasPageSize(chapter)).isFalse()
        assertThat(FixedPages.hasPageSize("<html><head></head><body><img src='a.png'/></body></html>")).isFalse()
    }

    @Test fun onlyTheBodysVisibleWordsCount() {
        // The title, the script and the &#160; filler are not text.
        assertThat(FixedPages.textLength(picturePage(2))).isEqualTo(0)
        assertThat(FixedPages.textLength(picturePage(2, "Megan and Lucy"))).isEqualTo("MeganandLucy".length)
    }

    @Test fun aPictureBookDeclaredReflowableOpensFixed() = runTest {
        val publication = ReadiumBookOpener(ApplicationProvider.getApplicationContext())
            .open(epub("pictures.epub", List(3) { picturePage(it + 1) }))!!
        try {
            assertThat(publication.metadata.layout).isEqualTo(Layout.FIXED)
            val scan = FixedPages.scan(publication, stopAtUnsized = false)
            assertThat(scan.allSized).isTrue()
            // Nothing but pictures: no Listen, no Search in book.
            assertThat(scan.hasText).isFalse()
            // The pages still get the book CSS, once.
            val html = String(publication.get(publication.readingOrder.first())!!.read().getOrNull()!!)
            assertThat(html.split(BookCss.STYLE).size - 1).isEqualTo(1)
        } finally {
            publication.close()
        }
    }

    @Test fun pagesWithATextLayerHaveTextToReadAloud() = runTest {
        // Prunelle's Jekyll and Hyde: pictures with the PDF's words laid over them.
        val words = "Utterson profita d'une réception que donnait le Dr Jekyll pour enfin aborder le sujet de l'étrange Mr Hyde."
        val publication = ReadiumBookOpener(ApplicationProvider.getApplicationContext())
            .open(epub("jekyll.epub", List(3) { picturePage(it + 1, words) }))!!
        try {
            assertThat(publication.metadata.layout).isEqualTo(Layout.FIXED)
            assertThat(FixedPages.scan(publication, stopAtUnsized = false).hasText).isTrue()
        } finally {
            publication.close()
        }
    }

    @Test fun aBookWithOneReflowingPageStaysReflowable() = runTest {
        val publication = ReadiumBookOpener(ApplicationProvider.getApplicationContext())
            .open(epub("mixed.epub", listOf(picturePage(1), chapter, chapter)))!!
        try {
            assertThat(publication.metadata.layout).isNotEqualTo(Layout.FIXED)
            val scan = FixedPages.scan(publication, stopAtUnsized = true)
            assertThat(scan.allSized).isFalse()
            assertThat(scan.hasText).isTrue()
        } finally {
            publication.close()
        }
    }

    @Test fun aFixedBookShowsOnePageAtATime() {
        assertThat(ReadingPrefs().toEpub(fixedLayout = true).spread).isEqualTo(Spread.NEVER)
        assertThat(ReadingPrefs().toEpub().spread).isNull()
    }

    private fun epub(name: String, pages: List<String>): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            fun put(entry: String, text: String) { zip.putNextEntry(ZipEntry(entry)); zip.write(text.toByteArray()) }
            put("META-INF/container.xml", """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val items = pages.indices.joinToString("\n") {
                """<item id="html$it" href="page${it + 1}.xhtml" media-type="application/xhtml+xml"/>"""
            }
            // As Kotobee writes them: reflowable for the book, pre-paginated on each spine item (which Readium drops).
            val spine = pages.indices.joinToString("\n") { """<itemref idref="html$it" properties="rendition:layout-pre-paginated"/>""" }
            put("EPUB/package.opf", """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0"
                  prefix="rendition: http://www.idpf.org/vocab/rendition/#">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">$name</dc:identifier>
                <dc:title>Pictures</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2022-09-22T12:54:49Z</meta>
                <meta property="rendition:layout">reflowable</meta></metadata>
                <manifest><item id="css" href="pdf.css" media-type="text/css"/>
                $items</manifest>
                <spine>$spine</spine></package>""")
            put("EPUB/pdf.css", "#fxlChapter { position: relative; }")
            pages.forEachIndexed { i, page -> put("EPUB/page${i + 1}.xhtml", page) }
        }
        return file
    }
}
