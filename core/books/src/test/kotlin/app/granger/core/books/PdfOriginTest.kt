package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import app.granger.core.books.PdfOrigin.Companion.PageLook
import app.granger.core.books.TextPdfFixtures.Line
import app.granger.core.books.TextPdfFixtures.Page
import app.granger.core.books.TextPdfFixtures.Picture
import com.google.common.truth.Truth.assertThat
import java.io.File
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
class PdfOriginTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    private val prose = (1..12).map { "Line $it of a page of prose, long enough to count as text on its page." }

    private fun pdf(name: String, page: (Int) -> Page): File = TextPdfFixtures.build(tmp.root, name, (1..6).map(page))

    @Test fun realFontsDrawnVisiblyAreBornDigital() {
        val file = pdf("novel.pdf") { Page(TextPdfFixtures.body(72f, *prose.toTypedArray())) }
        assertThat(PdfOrigin.of(file)).isEqualTo(PdfOrigin.BORN_DIGITAL)
    }

    @Test fun aFigureHereAndThereIsStillBornDigital() {
        val file = pdf("textbook.pdf") { n ->
            Page(TextPdfFixtures.body(72f, *prose.toTypedArray()), if (n % 2 == 0) listOf(Picture(72f, 300f, 300f, 200f)) else emptyList())
        }
        assertThat(PdfOrigin.of(file)).isEqualTo(PdfOrigin.BORN_DIGITAL)
    }

    @Test fun hiddenTextOverAPictureOfThePageIsAScan() {
        val file = pdf("scan.pdf") {
            Page(prose.mapIndexed { i, t -> Line(t, 72f + 14f * i, hidden = true) }, listOf(Picture(0f, 0f, TextPdfFixtures.WIDTH, TextPdfFixtures.HEIGHT)))
        }
        assertThat(PdfOrigin.of(file)).isEqualTo(PdfOrigin.SCAN)
    }

    @Test fun picturesWithoutTextAreImages() {
        val file = pdf("comic.pdf") { Page(emptyList(), listOf(Picture(0f, 0f, TextPdfFixtures.WIDTH, TextPdfFixtures.HEIGHT))) }
        assertThat(PdfOrigin.of(file)).isEqualTo(PdfOrigin.IMAGES)
    }

    @Test fun classifyRules() {
        val digital = PageLook(visible = 1500, hidden = 0, glyphless = 0, pageImage = false)
        val ocr = PageLook(visible = 0, hidden = 0, glyphless = 1500, pageImage = true)
        val ocrInRealFonts = PageLook(visible = 1500, hidden = 0, glyphless = 0, pageImage = true)   // ABBYY's visible OCR over the scan
        val picture = PageLook(0, 0, 0, pageImage = true)
        assertThat(PdfOrigin.classify(List(10) { digital } + List(2) { picture })).isEqualTo(PdfOrigin.BORN_DIGITAL)
        assertThat(PdfOrigin.classify(List(12) { ocr })).isEqualTo(PdfOrigin.SCAN)
        assertThat(PdfOrigin.classify(List(12) { ocrInRealFonts })).isEqualTo(PdfOrigin.SCAN)
        assertThat(PdfOrigin.classify(List(11) { picture } + digital)).isEqualTo(PdfOrigin.IMAGES)
        assertThat(PdfOrigin.classify(emptyList())).isEqualTo(PdfOrigin.UNKNOWN)
    }
}
