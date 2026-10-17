package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XhtmlTextTest {
    @get:Rule val tmp = TemporaryFolder()

    private val body = (1..60).joinToString("") { "<p>Paragraph number $it with some filler words to pad it out a bit.</p>" } +
        "<div>div with direct text<p>nested block</p></div><blockquote><p>quoted</p></blockquote>"

    private fun chunks(): List<TextChunk> {
        val file = TestEpubs.build(tmp.root, chapters = listOf(body))
        return ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }
    }

    @Test fun chunksAreDeterministicAndOrdered() {
        val a = chunks()
        assertThat(chunks()).isEqualTo(a)
        assertThat(a.map { it.ordinal }).isEqualTo(a.indices.toList())
        assertThat(a.size).isGreaterThan(1)
        assertThat(a.dropLast(1).all { it.text.length >= 400 }).isTrue()
        assertThat(a.all { it.text.length <= 2000 }).isTrue()
        val all = a.joinToString(" ") { it.text }
        assertThat(all).contains("Paragraph number 1 ")
        assertThat(all.split("nested block")).hasSize(2)
        assertThat(all.split("quoted")).hasSize(2)
    }

    @Test fun progressionRisesFromZeroToBelowOne() {
        val a = chunks()
        assertThat(a.first().progression).isEqualTo(0.0)
        assertThat(a.map { it.progression }).isInStrictOrder()
        assertThat(a.last().progression).isLessThan(1.0)
    }

    @Test fun longBlockIsCutAtTwoThousand() {
        val file = TestEpubs.build(tmp.root, chapters = listOf("<p>" + "word ".repeat(1000) + "</p>"))
        val cs = ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }
        assertThat(cs.size).isAtLeast(3)
        assertThat(cs.all { it.text.length <= 2000 }).isTrue()
    }

    @Test fun allWalksTheSpine() {
        val file = TestEpubs.build(tmp.root, chapters = listOf("<p>one</p>", "<p>two</p>"))
        val cs = XhtmlText.all(file, listOf("OEBPS/c001.xhtml", "OEBPS/c002.xhtml")).toList()
        assertThat(cs.map { it.href }).containsExactly("OEBPS/c001.xhtml", "OEBPS/c002.xhtml").inOrder()
        assertThat(cs.map { it.text }).containsExactly("one", "two").inOrder()
    }

    @Test fun missingHrefGivesEmpty() {
        val file = TestEpubs.build(tmp.root)
        assertThat(ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/nope.xhtml") }).isEmpty()
    }

    @Test fun corruptChapterGivesEmptyAndAllSkipsIt() {
        val file = TestEpubs.build(tmp.root, chapters = listOf("<p>" + "good ".repeat(50) + "</p>", "<p>" + "bad ".repeat(50) + "</p>"))
        TestEpubs.corrupt(file, "OEBPS/c001.xhtml")
        assertThat(ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }).isEmpty()
        val cs = XhtmlText.all(file, listOf("OEBPS/c001.xhtml", "OEBPS/c002.xhtml", "OEBPS/missing.xhtml")).toList()
        assertThat(cs.map { it.href }.distinct()).containsExactly("OEBPS/c002.xhtml")
    }

    @Test fun shortBlockBeforeLongBlockStillMakesFullChunks() {
        val short = "short ".repeat(16).trim()
        val long = "long ".repeat(390).trim()
        val file = TestEpubs.build(tmp.root, chapters = listOf("<p>$short</p><p>$long</p><p>tail</p>"))
        val cs = ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }
        assertThat(cs.dropLast(1).all { it.text.length >= 400 }).isTrue()
        assertThat(cs.all { it.text.length <= 2000 }).isTrue()
        assertThat(cs.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")).isEqualTo("$short $long tail")
    }

    @Test fun containerKeepsInlineTextAndSelfClosingDivIsFine() {
        val file = TestEpubs.build(tmp.root, chapters = listOf("<div/><div>Hello <em>world</em> and <b>more</b><p>x</p></div><p>y</p>"))
        val cs = ZipFile(file).use { XhtmlText.chunks(it, "OEBPS/c001.xhtml") }
        assertThat(cs.single().text).isEqualTo("Hello world and more\nx\ny")
    }
}
