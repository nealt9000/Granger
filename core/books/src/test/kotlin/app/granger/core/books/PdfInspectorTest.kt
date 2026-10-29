package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    @Test fun readsTheInfoDictionaryAndOutline() {
        val result = PdfInspector.inspect(PdfFixtures.text(tmp.root), "fallback")
        assertThat(result).isInstanceOf(Inspection.Ok::class.java)
        val meta = (result as Inspection.Ok).meta
        assertThat(meta.title).isEqualTo(PdfFixtures.TITLE)
        assertThat(meta.authors).containsExactly("A. Field")
        assertThat(meta.subjects).containsExactly("Water")
        assertThat(meta.pageCount).isEqualTo(3)
        assertThat(meta.outline)
            .containsExactly(OutlineEntry("Boiling", 1), OutlineEntry("Filters", 2), OutlineEntry("Storage", 3))
            .inOrder()
        assertThat(meta.spine).isEmpty()
    }

    @Test fun theOutlineIsReadByEveryKindOfDestinationWithItsDepth() {
        assertThat(PdfInspector.outline(PdfFixtures.linked(tmp.root))).containsExactly(
            OutlineEntry("Water", 1, 0), OutlineEntry("Boiling", 2, 1), OutlineEntry("Filters", 3, 1), OutlineEntry("Storage", 4, 1),
        ).inOrder()
        assertThat(PdfInspector.outline(PdfFixtures.imageOnly(tmp.root))).isEmpty()
        assertThat(PdfInspector.outline(PdfFixtures.truncated(tmp.root))).isEmpty()
    }

    @Test fun noInfoTitleTakesTheFallback() {
        val file = File(tmp.root, "untitled.pdf")
        PDDocument().use { doc ->
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.save(file)
        }
        val meta = (PdfInspector.inspect(file, "Untitled Booklet") as Inspection.Ok).meta
        assertThat(meta.title).isEqualTo("Untitled Booklet")
        assertThat(meta.authors).isEmpty()
    }

    @Test fun encryptedIsProtected() {
        assertThat(PdfInspector.inspect(PdfFixtures.encrypted(tmp.root)))
            .isEqualTo(Inspection.Protected(DrmKind.PDF_ENCRYPTED))
    }

    @Test fun truncatedIsInvalid() {
        assertThat(PdfInspector.inspect(PdfFixtures.truncated(tmp.root))).isInstanceOf(Inspection.Invalid::class.java)
    }

    @Test fun zeroPagesIsInvalid() {
        val file = File(tmp.root, "empty.pdf")
        PDDocument().use { it.save(file) }
        assertThat(PdfInspector.inspect(file)).isEqualTo(Inspection.Invalid("no pages"))
    }

    @Test fun notAPdfIsInvalid() {
        val file = File(tmp.root, "x.pdf").apply { writeText("hello") }
        assertThat(PdfInspector.inspect(file)).isInstanceOf(Inspection.Invalid::class.java)
    }
}
