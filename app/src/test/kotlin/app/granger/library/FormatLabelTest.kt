package app.granger.library

import app.granger.book
import app.granger.core.library.BookFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FormatLabelTest {
    private fun app.granger.UiText?.en(): String? = this?.resolve(ApplicationProvider.getApplicationContext())

    @Test fun onlyPdfsSayTheirFormat() {
        assertThat(formatLabel(book("e"))).isNull()
        assertThat(formatLabel(book("p").copy(format = BookFormat.PDF)).en()).isEqualTo("PDF")
        assertThat(formatLabel(book("s").copy(format = BookFormat.PDF, searchable = false)).en()).isEqualTo("PDF · Not searchable")
    }
}
