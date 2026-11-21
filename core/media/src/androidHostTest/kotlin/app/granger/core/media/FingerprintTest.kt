package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FingerprintTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fingerprintIsStableAndSizeSensitive() {
        val head = "head".toByteArray()
        val tail = "tail".toByteArray()
        val fp = Fingerprint.of(1_000L, head, tail)
        assertThat(fp).matches("[0-9a-f]{16}")
        assertThat(Fingerprint.of(1_000L, head.copyOf(), tail.copyOf())).isEqualTo(fp)
        assertThat(Fingerprint.of(1_001L, head, tail)).isNotEqualTo(fp)
        assertThat(Fingerprint.of(1_000L, head, "tail!".toByteArray())).isNotEqualTo(fp)
        val expected = MessageDigest.getInstance("SHA-1").digest("1000:".toByteArray() + head + tail)
            .joinToString("") { "%02x".format(it) }.take(16)
        assertThat(fp).isEqualTo(expected)
    }

    @Test fun fileFingerprintHashesSizeHeadAndTail() {
        val bytes = Random(7).nextBytes(200_000)
        val file = tmp.newFile().apply { writeBytes(bytes) }
        val fp = FileInputStream(file).use { Fingerprint.of(it.channel) }
        assertThat(fp).isEqualTo(Fingerprint.of(200_000L, bytes.copyOfRange(0, 65_536), bytes.copyOfRange(200_000 - 65_536, 200_000)))

        // A small file: head and tail are the whole file, overlapping.
        val small = tmp.newFile().apply { writeBytes(bytes.copyOf(10)) }
        assertThat(FileInputStream(small).use { Fingerprint.of(it.channel) })
            .isEqualTo(Fingerprint.of(10L, bytes.copyOf(10), bytes.copyOf(10)))

        // A change in the middle, past both ends, isn't seen: the fingerprint only reads the ends.
        val middle = bytes.copyOf().also { it[100_000] = (it[100_000] + 1).toByte() }
        val edited = tmp.newFile().apply { writeBytes(middle) }
        assertThat(FileInputStream(edited).use { Fingerprint.of(it.channel) }).isEqualTo(fp)
    }
}
