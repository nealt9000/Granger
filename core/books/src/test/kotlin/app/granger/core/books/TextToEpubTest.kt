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

    @Test fun aFileWithOneParagraphPerLineSplitsAtEachLine() {
        val para = "This sentence is a whole paragraph of a file written without blank lines, the way many notes are. ".repeat(2).trim()
        val ps = Regex("<p>(.*?)</p>").findAll(chapters(convert((1..20).joinToString("\n") { "$it $para" })).single()).toList()
        assertThat(ps).hasSize(20)
    }

    @Test fun hardWrappedTextKeepsItsLinesTogether() {
        val wrapped = (1..40).joinToString("\n") { "a line of an ordinary hard-wrapped book, about seventy characters" }
        val ps = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(chapters(convert(wrapped + "\n\n" + wrapped)).single()).toList()
        assertThat(ps).hasSize(2)
    }

    @Test fun aTextNamesItsOwnTitle() {
        assertThat(TextToEpub.titleOf("# Field notes\n\nSome text.", markdown = true)).isEqualTo("Field notes")
        assertThat(TextToEpub.titleOf("The Project Gutenberg eBook of Walden, by Henry David Thoreau\n\nTitle: Walden\n\nAuthor: Thoreau", false))
            .isEqualTo("Walden")
        assertThat(TextToEpub.titleOf("\uFEFFThe Project Gutenberg eBook of Walden, by Henry David Thoreau\n", false)).isEqualTo("Walden")
        assertThat(TextToEpub.titleOf("\n\nThe Wind in the Willows\n\nby Kenneth Grahame\n\nChapter 1", false)).isEqualTo("The Wind in the Willows")
        // A first line that's a sentence, a heading or runs on is no title: the file name stays.
        assertThat(TextToEpub.titleOf("Bought milk today.\n\nThen eggs.", false)).isNull()
        assertThat(TextToEpub.titleOf("Chapter 1\n\nIt began.", false)).isNull()
        assertThat(TextToEpub.titleOf("first line of a paragraph\nthat carries on here", false)).isNull()
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

    private fun opf(file: File): String = ZipFile(file).use { z -> z.getInputStream(z.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8) }

    @Test fun theLanguageIsDeclaredElseGuessed() {
        val spanish = "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero."
        val english = "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife."
        assertThat(opf(convert(spanish))).contains("<dc:language>es</dc:language>")
        assertThat(opf(convert(english))).contains("<dc:language>en</dc:language>")
        val declared = ByteArrayOutputStream().also { TextToEpub.convert(english, "T", false, it, language = "spa") }
        assertThat(opf(tmp.newFile().apply { writeBytes(declared.toByteArray()) })).contains("<dc:language>es</dc:language>")
    }
}
