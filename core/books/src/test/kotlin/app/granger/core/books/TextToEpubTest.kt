package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextToEpubTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun convert(text: String, markdown: Boolean = false, max: Int = 100_000): File {
        val out = ByteArrayOutputStream()
        TextToEpub.convert(text, "My Title", markdown, out, max)
        return tmp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    private fun chapters(file: File): List<String> = ZipFile(file).use { z ->
        z.entries().asSequence().map { it.name }.filter { it.matches(Regex("OEBPS/c\\d{3}\\.xhtml")) }.sorted()
            .map { n -> z.getInputStream(z.getEntry(n)).readBytes().toString(Charsets.UTF_8) }.toList()
    }

    @Test fun chapterHeadingsSplitTxt() {
        val text = "Intro line\n\nChapter 1\n\nFirst <body> & text.\n\nCHAPTER II\n\nSecond text.\n\nPart 3\n\nThird."
        val file = convert(text)
        val cs = chapters(file)
        assertThat(cs).hasSize(4)
        assertThat(cs[1]).contains("Chapter 1")
        assertThat(cs[1]).contains("First &lt;body&gt; &amp; text.")
        assertThat(cs[2]).contains("CHAPTER II")
    }

    @Test fun singleHeadingDoesNotSplit() {
        assertThat(chapters(convert("Chapter 1\n\nonly one\n\nmore"))).hasSize(1)
    }

    @Test fun fiveMegabytesWithoutHeadingsSplitsBySize() {
        val para = "word ".repeat(100).trim() + "."
        val paragraphs = 5_000_000 / (para.length + 2)
        val text = (1..paragraphs).joinToString("\n\n") { para }
        val cs = chapters(convert(text))
        assertThat(cs.size).isAtLeast(50)
        var total = 0
        for (c in cs) {
            val ps = Regex("<p>(.*?)</p>").findAll(c).map { it.groupValues[1] }.toList()
            assertThat(ps.sumOf { it.length }).isAtMost(100_000 + para.length)
            assertThat(ps.all { it == para }).isTrue()
            total += ps.size
        }
        assertThat(total).isEqualTo(paragraphs)
    }

    @Test fun overlongParagraphIsCutAtSpace() {
        val para = "abc ".repeat(100).trim()
        val cs = chapters(convert(para, max = 50))
        assertThat(cs.size).isAtLeast(2)
        assertThat(cs.joinToString(" ") { c -> Regex("<p>(.*?)</p>").findAll(c).joinToString(" ") { it.groupValues[1] } }).isEqualTo(para)
    }

    @Test fun markdownHeadingsBecomeChapters() {
        val md = "# One\n\nalpha\n\n# Two\n\nbeta ![img](x.png)\n\n## sub\n\ngamma"
        val cs = chapters(convert(md, markdown = true))
        assertThat(cs).hasSize(2)
        assertThat(cs[0]).contains("alpha")
        assertThat(cs[1]).contains("gamma")
        assertThat(cs[1]).doesNotContain("<img")
    }

    @Test fun markdownScriptIsEscaped() {
        val cs = chapters(convert("# A\n\n<script>alert(1)</script>\n\n# B\n\nok", markdown = true))
        assertThat(cs.none { it.contains("<script") }).isTrue()
    }

    @Test fun outputPassesInspector() {
        for (md in listOf(false, true)) {
            val result = EpubInspector.inspect(convert("# Hi\n\ntext\n\n# Two\n\nmore", markdown = md))
            assertThat(result).isInstanceOf(Inspection.Ok::class.java)
            assertThat((result as Inspection.Ok).meta.title).isEqualTo("My Title")
        }
    }

    @Test fun mimetypeIsFirstAndStored() {
        ZipFile(convert("hello")).use { z ->
            val first = z.entries().nextElement()
            assertThat(first.name).isEqualTo("mimetype")
            assertThat(first.method).isEqualTo(java.util.zip.ZipEntry.STORED)
        }
    }

    @Test fun markdownJavascriptLinkIsSanitised() {
        val cs = chapters(convert("# A\n\n[x](javascript:alert(1))\n\n# B\n\n[y](JavaScript:evil)", markdown = true))
        assertThat(cs.none { it.contains("javascript:", ignoreCase = true) }).isTrue()
    }

    private fun assertWellFormed(xml: String) {
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }

    @Test fun xmlIllegalCharactersAreStripped() {
        val dirty = "\u0000bad\u000C text \uFFFF\uD800 ok"
        for (md in listOf(false, true)) {
            val out = ByteArrayOutputStream()
            TextToEpub.convert("# T$dirty\n\nbody$dirty\n\n# U\n\nmore", "Title$dirty", md, out)
            val file = tmp.newFile().apply { writeBytes(out.toByteArray()) }
            assertThat(EpubInspector.inspect(file)).isInstanceOf(Inspection.Ok::class.java)
            ZipFile(file).use { z ->
                z.entries().asSequence().filter { it.name.endsWith(".xhtml") || it.name.endsWith(".opf") }.forEach {
                    assertWellFormed(z.getInputStream(it).readBytes().toString(Charsets.UTF_8))
                }
            }
            assertThat(chapters(file).joinToString("")).contains("bad text  ok")
        }
    }
}
