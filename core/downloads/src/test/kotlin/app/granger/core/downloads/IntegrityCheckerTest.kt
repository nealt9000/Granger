package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class IntegrityCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = Random(4).nextBytes(300_000)

    private fun record(file: File, verifiedAt: Long? = null) = DownloadRecord(
        itemId = "med", hash = sha256(content), kind = ItemKind.PACK, title = "WikiMed", urls = emptyList(),
        sizeBytes = content.size.toLong(), filePath = file.path, snapshotDate = "2026-10-10", wifiOnly = true,
        state = DownloadState.INSTALLED, verifiedAt = verifiedAt, createdAt = 1, updatedAt = 1,
    )

    @Test fun quickChecksPresenceAndSize() {
        val file = File(tmp.root, "med.zim")
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.MISSING)
        file.writeBytes(content.copyOf(1_000))
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.INCOMPLETE)
        file.writeBytes(content)
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.SIZE_OK)
        assertThat(IntegrityChecker.quick(record(file, verifiedAt = 5))).isEqualTo(Integrity.VERIFIED)
    }

    @Test fun fullCheckVerifiesWithProgress() = runTest {
        val file = File(tmp.root, "med.zim").apply { writeBytes(content) }
        val progress = mutableListOf<Float>()
        assertThat(IntegrityChecker.full(record(file)) { progress += it }).isEqualTo(Integrity.VERIFIED)
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
    }

    @Test fun fullCheckUsesTheRecordsAlgorithm() = runTest {
        val file = File(tmp.root, "med.zim").apply { writeBytes(content) }
        val good = record(file).copy(hash = sha1(content), hashAlgorithm = HashAlgorithm.SHA1)
        assertThat(IntegrityChecker.full(good)).isEqualTo(Integrity.VERIFIED)
        val bad = good.copy(hash = "0".repeat(40))
        assertThat(IntegrityChecker.full(bad)).isEqualTo(Integrity.CORRUPTED)
    }

    @Test fun fullCheckFindsCorruption() = runTest {
        val damaged = content.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        val file = File(tmp.root, "med.zim").apply { writeBytes(damaged) }
        assertThat(IntegrityChecker.full(record(file, verifiedAt = 5))).isEqualTo(Integrity.CORRUPTED)
    }

    @Test fun fullCheckOfMissingOrShortFiles() = runTest {
        val file = File(tmp.root, "med.zim")
        assertThat(IntegrityChecker.full(record(file))).isEqualTo(Integrity.MISSING)
        file.writeBytes(content.copyOf(10))
        assertThat(IntegrityChecker.full(record(file))).isEqualTo(Integrity.INCOMPLETE)
    }
}
