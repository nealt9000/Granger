package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    @Test fun chunksAreKeyedByPage() {
        val chunks = PdfText.all(PdfFixtures.text(tmp.root)).toList()
        assertThat(chunks.map { it.href }.distinct()).containsExactly("page/1", "page/2", "page/3").inOrder()
        for ((_, page) in chunks.groupBy { it.href }) {
            assertThat(page.map { it.ordinal }).isEqualTo(page.indices.toList())
            assertThat(page.all { it.progression >= 0.0 && it.progression < 1.0 }).isTrue()
        }
    }

    @Test fun extractionIsDeterministic() {
        val file = PdfFixtures.text(tmp.root)
        val all = PdfText.all(file).toList()
        assertThat(PdfText.all(file).toList()).isEqualTo(all)
        assertThat(PdfText.chunks(file, "page/2")).isEqualTo(all.filter { it.href == "page/2" })
    }

    @Test fun pageTwoHoldsItsPhraseAndHyphensJoin() {
        val text = PdfText.chunks(PdfFixtures.text(tmp.root), "page/2").joinToString(" ") { it.text }
        assertThat(text).contains(PdfFixtures.PAGE_TWO_PHRASE)
        assertThat(text).contains("example")
        assertThat(text).doesNotContain("exam-")
    }

    @Test fun archiveOrgNotSignHyphensJoin() {
        val text = PdfText.chunks(PdfFixtures.text(tmp.root), "page/3").joinToString(" ") { it.text }
        assertThat(text).contains("covered containers")
        assertThat(text).doesNotContain("\u00ac")
    }

    @Test fun repeatedHeadsAndFootersAreDropped() {
        val text = PdfText.all(PdfFixtures.text(tmp.root)).joinToString(" ") { it.text }
        assertThat(text).doesNotContain(PdfFixtures.HEAD)
        assertThat(text).doesNotContain("Page 2")
        // The page's own heading stays.
        assertThat(text).contains("Filters")
    }

    @Test fun longPagesStayWithinTheWindowAndNeverSpanPages() {
        val file = PdfFixtures.long(tmp.root, 40)
        val chunks = PdfText.all(file).toList()
        assertThat(chunks.map { it.href }.distinct()).hasSize(40)
        for ((href, page) in chunks.groupBy { it.href }) {
            assertThat(page.all { it.text.length <= MAX_CHUNK }).isTrue()
            assertThat(page.dropLast(1).all { it.text.length >= MIN_CHUNK }).isTrue()
            val n = PdfText.pageOf(href)
            assertThat(page.all { it.text.contains("Page $n line") }).isTrue()
        }
    }

    @Test fun scanHasNoText() {
        val scan = PdfFixtures.imageOnly(tmp.root)
        assertThat(PdfText.all(scan).toList()).isEmpty()
        assertThat(PdfText.hasTextLayer(scan)).isFalse()
        assertThat(PdfText.hasTextLayer(PdfFixtures.text(tmp.root))).isTrue()
    }

    @Test fun badHrefsAndFilesGiveNothing() {
        val file = PdfFixtures.text(tmp.root)
        assertThat(PdfText.chunks(file, "page/9")).isEmpty()
        assertThat(PdfText.chunks(file, "OEBPS/c001.xhtml")).isEmpty()
        assertThat(PdfText.chunks(PdfFixtures.passwordLocked(tmp.root), "page/1")).isEmpty()
        assertThat(PdfText.chunks(PdfFixtures.encrypted(tmp.root), "page/1")).isNotEmpty()   // owner password only
        assertThat(PdfText.chunks(PdfFixtures.truncated(tmp.root), "page/1")).isEmpty()
    }

    @Test fun pageKeys() {
        assertThat(PdfText.href(12)).isEqualTo("page/12")
        assertThat(PdfText.pageOf("page/12")).isEqualTo(12)
        assertThat(PdfText.pageOf("OEBPS/c.xhtml")).isNull()
    }
}
