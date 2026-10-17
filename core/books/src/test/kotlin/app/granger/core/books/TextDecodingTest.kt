package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextDecodingTest {
    @Test fun utf8() {
        assertThat(TextDecoding.decode("héllo".toByteArray())).isEqualTo("héllo")
    }

    @Test fun utf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()
        assertThat(TextDecoding.decode(bytes)).isEqualTo("héllo")
    }

    @Test fun utf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi é".toByteArray(Charsets.UTF_16LE)
        assertThat(TextDecoding.decode(bytes)).isEqualTo("hi é")
    }

    @Test fun utf16BeBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi é".toByteArray(Charsets.UTF_16BE)
        assertThat(TextDecoding.decode(bytes)).isEqualTo("hi é")
    }

    @Test fun windows1252CurlyQuotes() {
        assertThat(TextDecoding.decode(byteArrayOf(0x93.toByte(), 0x94.toByte()))).isEqualTo("“”")
    }
}
