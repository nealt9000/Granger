package app.granger.library

import app.granger.book
import app.granger.core.library.BookFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatLabelTest {
    @Test fun onlyPdfsSayTheirFormat() {
        assertThat(formatLabel(book("e"))).isNull()
        assertThat(formatLabel(book("p").copy(format = BookFormat.PDF))).isEqualTo("PDF")
        assertThat(formatLabel(book("s").copy(format = BookFormat.PDF, searchable = false))).isEqualTo("PDF · Not searchable")
    }
}
