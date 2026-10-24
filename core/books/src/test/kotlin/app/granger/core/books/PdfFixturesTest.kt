package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfFixturesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    @Test fun everyFixtureIsAPdf() {
        val dir = tmp.root
        for (file in listOf(
            PdfFixtures.text(dir),
            PdfFixtures.imageOnly(dir),
            PdfFixtures.encrypted(dir),
            PdfFixtures.truncated(dir),
            PdfFixtures.long(dir, 5),
        )) {
            assertThat(String(file.readBytes().copyOf(4))).isEqualTo("%PDF")
        }
    }

    @Test fun textReloadsWithThreePages() {
        PDDocument.load(PdfFixtures.text(tmp.root)).use { assertThat(it.numberOfPages).isEqualTo(3) }
    }

    @Test fun encryptedIsEncrypted() {
        PDDocument.load(PdfFixtures.encrypted(tmp.root)).use { assertThat(it.isEncrypted).isTrue() }
    }
}
