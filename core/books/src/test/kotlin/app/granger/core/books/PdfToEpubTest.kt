package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import app.granger.core.books.TextPdfFixtures.Line
import app.granger.core.books.TextPdfFixtures.Page
import app.granger.core.books.TextPdfFixtures.Picture
import app.granger.core.books.pdfepub.ChapterSource
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipFile
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfToEpubTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    private val mono by lazy { PDType1Font.COURIER }

    /** Justified-looking prose: every line the same width, in a monospaced face. */
    private fun prose(top: Float, vararg lines: String, x: Float = 72f): List<Line> =
        lines.mapIndexed { i, t -> Line(t, top + 14f * i, x, 10f, mono) }

    /**
     * A page of prose from [top]: 64-character lines in paragraphs of eight, each opening indented and closing with a
     * full stop, as a book sets them.
     */
    private fun filler(top: Float, count: Int, word: String = "water"): List<Line> =
        (0 until count).map { i ->
            val text = fill(word, i).let { if (i % 8 == 7 || i == count - 1) it.dropLast(1) + "." else it }
            Line(text, top + 14f * i, if (i % 8 == 0) 90f else 72f, 10f, mono)
        }

    private fun fill(word: String, i: Int): String {
        val base = "the $word line $i keeps on going with plain words of the sort a book has"
        return (base + " and more words to fill it out").take(64)
    }

    private fun convert(pages: List<Page>, outline: List<Pair<String, Int>> = emptyList(), name: String = "b.pdf"): Pair<PdfToEpub.Report, File> {
        val pdf = TextPdfFixtures.build(tmp.root, name, pages, outline, title = "The Test Book")
        val epub = File(tmp.root, name.removeSuffix(".pdf") + ".epub")
        return PdfToEpub.convert(pdf, epub) to epub
    }

    /** The EPUB's chapters' text, tags stripped, in spine order; and the contents' titles. */
    private fun chapters(epub: File): List<String> = ZipFile(epub).use { z ->
        z.entries().toList().map { it.name }.filter { Regex("OEBPS/c\\d+\\.xhtml").matches(it) }.sorted().map { n ->
            z.getInputStream(z.getEntry(n)).readBytes().decodeToString().substringAfter("<body>").substringBefore("</body>")
        }
    }

    private fun nav(epub: File): List<String> = ZipFile(epub).use { z ->
        Regex("<a href=\"[^\"]*\">([^<]*)</a>").findAll(z.getInputStream(z.getEntry("OEBPS/nav.xhtml")).readBytes().decodeToString())
            .map { it.groupValues[1] }.toList()
    }

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    @Test fun runningHeadsAndPageNumbersAreDropped() {
        val pages = (1..6).map { n ->
            Page(
                listOf(Line(if (n % 2 == 0) "THE TEST BOOK" else "A Chapter Title", 40f, 250f, 9f)) +
                    filler(90f, 40) + Line("$n", 760f, 300f, 9f),
            )
        }
        val (report, epub) = convert(pages)
        assertThat(report.passes).isTrue()
        val all = chapters(epub).joinToString(" ") { text(it) }.removePrefix("Pages 1–6 ")
        assertThat(all).doesNotContain("THE TEST BOOK")
        assertThat(all).doesNotContain("A Chapter Title")
        // Only the lines' own numbers are left: "line 3 keeps".
        assertThat(Regex("\\b\\d+\\b").findAll(all).map { it.range.first }.all { all.substring(it).matches(Regex("\\d+ keeps.*", RegexOption.DOT_MATCHES_ALL)) }).isTrue()
        assertThat(report.droppedChars).isGreaterThan(0)
        assertThat(report.keptShare).isAtLeast(0.98f)
    }

    @Test fun brokenWordsJoinAndAParagraphRunsOnAcrossThePage() {
        val pages = listOf(
            Page(filler(72f, 38) + prose(72f + 14f * 38, "and here the sentence runs to the end of the page and then con-")),
            Page(prose(72f, "tinues on the next page without a break.") + filler(100f, 38, "sand")),
        )
        val (report, epub) = convert(pages)
        assertThat(report.passes).isTrue()
        val html = chapters(epub).joinToString("")
        assertThat(text(html)).contains("then continues on the next page without a break.")
        // One paragraph: the second page's first line joined the first page's last.
        assertThat(html).doesNotContain("then con-</p>")
    }

    @Test fun verseKeepsItsLines() {
        val verse = listOf(
            "Tell me about a complicated man.",
            "Muse, tell me how he wandered and was lost",
            "when he had wrecked the holy town of Troy,",
            "and where he went, and who he met, the pain",
            "he suffered in the storms at sea, and how",
            "he worked to save his life and bring his men",
            "back home. He failed to keep them safe; poor fools,",
            "they ate the Sun God’s cattle, and the god",
            "kept them from home. Now goddess, child of Zeus,",
            "tell the old story for our modern times.",
        )
        val pages = (1..4).map { Page(TextPdfFixtures.body(72f, *(verse + verse + verse).toTypedArray())) }
        val (report, epub) = convert(pages)
        assertThat(report.passes).isTrue()
        assertThat(report.verseLines).isEqualTo(4 * 30)
        val html = chapters(epub).first()
        assertThat(html).contains("<p class=\"line\">Tell me about a complicated man.</p><p class=\"line\">Muse, tell me how he wandered and was lost</p>")
    }

    @Test fun theOutlineMakesChaptersSplitAtTheirTitleLines() {
        val pages = listOf(
            Page(listOf(Line("ONE", 72f, 72f, 18f, TextPdfFixtures.BOLD)) + filler(110f, 38)),
            Page(filler(72f, 20) + Line("TWO", 400f, 72f, 18f, TextPdfFixtures.BOLD) + filler(440f, 20, "sand")),
            Page(filler(72f, 40, "sand")),
            Page(listOf(Line("THREE", 72f, 72f, 18f, TextPdfFixtures.BOLD)) + filler(110f, 38, "salt")),
        )
        val (report, epub) = convert(pages, outline = listOf("One" to 1, "Two" to 2, "Three" to 4))
        assertThat(report.chapterSource).isEqualTo(ChapterSource.OUTLINE)
        assertThat(nav(epub)).containsExactly("One", "Two", "Three").inOrder()
        val (one, two, three) = chapters(epub)
        assertThat(text(one)).contains("the water line 19 ")          // page 2's first half stays in chapter one
        assertThat(text(two)).doesNotContain("water line")
        assertThat(two).startsWith("<h1>TWO</h1>")
        assertThat(text(two)).contains("the sand line 0")
        assertThat(three).startsWith("<h1>THREE</h1>")
    }

    @Test fun withoutAnOutlineBigHeadingsMakeChapters() {
        val pages = listOf(
            Page(listOf(Line("Morning", 72f, 72f, 18f)) + filler(110f, 38)),
            Page(filler(72f, 40)),
            Page(listOf(Line("Evening", 72f, 72f, 18f)) + filler(110f, 38, "salt")),
        )
        val (report, epub) = convert(pages)
        assertThat(report.chapterSource).isEqualTo(ChapterSource.HEADINGS)
        assertThat(nav(epub)).containsExactly("Morning", "Evening").inOrder()
    }

    @Test fun withNeitherEveryTwentyPagesIsAChapter() {
        val pages = (1..25).map { Page(filler(72f, 40)) }
        val (report, epub) = convert(pages)
        assertThat(report.chapterSource).isEqualTo(ChapterSource.PAGES)
        assertThat(nav(epub)).containsExactly("Pages 1–20", "Pages 21–25").inOrder()
    }

    @Test fun footnotesGatherAtTheChaptersEnd() {
        val pages = (1..3).map { n ->
            Page(filler(72f, 36) + Line("$n. A note in small type at the foot of page $n.", 700f, 72f, 8f))
        }
        val (report, epub) = convert(pages)
        assertThat(report.notes).isEqualTo(3)
        val html = chapters(epub).single()
        assertThat(html).contains("<section class=\"notes\"><h2>Notes</h2><p class=\"note\">1. A note in small type at the foot of page 1.</p>")
        assertThat(html.indexOf("A note in small type")).isGreaterThan(html.indexOf(fill("water", 35)))
    }

    @Test fun aPictureLandsBetweenTheParagraphsAroundIt() {
        val pages = listOf(
            Page(filler(72f, 10) + filler(500f, 10, "sand"), listOf(Picture(72f, 230f, 300f, 200f))),
            Page(filler(72f, 40, "salt")),
        )
        val (report, epub) = convert(pages)
        assertThat(report.pictures).isEqualTo(1)
        val html = chapters(epub).single()
        val figure = html.indexOf("<figure>")
        assertThat(figure).isGreaterThan(html.indexOf("the water line 9"))
        assertThat(figure).isLessThan(html.indexOf("the sand line 0"))
        ZipFile(epub).use { assertThat(it.getEntry("OEBPS/images/p1-1.png") ?: it.getEntry("OEBPS/images/p1-1.jpg")).isNotNull() }
    }

    @Test fun twoColumnsReadLeftThenRight() {
        val left = (0 until 40).map { Line("left column line $it of the page", 72f + 14f * it, 50f, 9f, mono) }
        val right = (0 until 40).map { Line("right column line $it of the page", 72f + 14f * it, 320f, 9f, mono) }
        val (report, epub) = convert(listOf(Page(left + right), Page(left + right)))
        assertThat(report.passes).isTrue()
        val t = text(chapters(epub).joinToString(" "))
        assertThat(t.indexOf("left column line 39")).isLessThan(t.indexOf("right column line 0"))
    }

    @Test fun aScanIsntConvertedAndNothingIsWritten() {
        val pages = (1..4).map {
            Page(filler(72f, 40).map { it.copy(hidden = true) }, listOf(Picture(0f, 0f, TextPdfFixtures.WIDTH, TextPdfFixtures.HEIGHT)))
        }
        val (report, epub) = convert(pages)
        assertThat(report.passes).isFalse()
        assertThat(report.origin).isEqualTo(PdfOrigin.SCAN)
        assertThat(epub.exists()).isFalse()
        assertThat(tmp.root.listFiles()!!.filter { it.name.startsWith(".incoming-") }).isEmpty()
    }

    @Test fun theEpubIsReadable() {
        val (_, epub) = convert((1..3).map { Page(filler(72f, 40)) })
        val meta = (EpubInspector.inspect(epub) as Inspection.Ok).meta
        assertThat(meta.title).isEqualTo("The Test Book")
    }

    @Test fun theGate() {
        val ok = PdfToEpub.Report(PdfOrigin.BORN_DIGITAL, 100, 100_000, 99_000, 500, 400, 300, 0, 10, 0, 0, 10, ChapterSource.OUTLINE, 0, true, "")
        assertThat(PdfToEpub.gate(ok)).isNull()
        assertThat(PdfToEpub.gate(ok.copy(keptChars = 80_000))).contains("kept only 80%")
        assertThat(PdfToEpub.gate(ok.copy(complexPages = 30))).contains("tables or three columns")
        assertThat(PdfToEpub.gate(ok.copy(medianParagraphChars = 20))).contains("too short")
        assertThat(PdfToEpub.gate(ok.copy(keptChars = 300, sourceChars = 300))).contains("too little text")
        assertThat(PdfToEpub.gate(ok.copy(runTogether = 0.3f))).contains("run together")
        assertThat(PdfToEpub.runTogether("Alarwtyseertafnoladleadrmupthhaitsw and some words https://example.com/averyveryverylongaddress"))
            .isWithin(0.001f).of(1f / 5f)
    }
}
