package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceUnpackerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A voice zip of [files] (name to bytes) with a manifest that matches them, unless [manifest] says otherwise. */
    private fun zip(name: String = "vits-piper-en_US-ljspeech-medium.zip", files: Map<String, ByteArray>, manifest: String? = null): File {
        val voices = tmp.newFolder("voices")
        val zip = File(voices, name)
        ZipOutputStream(zip.outputStream()).use { z ->
            val m = manifest ?: buildString {
                append("""{"id":"x","engine":"piper","files":[""")
                append(files.entries.joinToString(",") { (n, b) -> """{"name":"$n","sizeBytes":${b.size},"sha256":"${sha(b)}"}""" })
                append("]}")
            }
            z.putNextEntry(ZipEntry("manifest.json")); z.write(m.toByteArray()); z.closeEntry()
            for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return zip
    }

    private fun record(zip: File, verifiedAt: Long? = null) = DownloadRecord(
        itemId = "vits-piper-en_US-ljspeech-medium", hash = "0".repeat(64), kind = ItemKind.VOICE, title = "Granger voice",
        urls = listOf("https://x/v.zip"), sizeBytes = zip.length(), filePath = zip.path, snapshotDate = "", wifiOnly = true,
        state = DownloadState.INSTALLED, verifiedAt = verifiedAt, createdAt = 0L, updatedAt = 0L,
    )

    @Test fun unpacksIntoTheVoiceDirectoryAndDeletesTheZip() = runTest {
        val zip = zip(files = mapOf("model.onnx" to ByteArray(10) { 1 }, "tokens.txt" to "a\n".toByteArray(), "espeak-ng-data/en_dict" to ByteArray(3)))
        val dir = VoiceUnpacker.unpack(zip, Dispatchers.Unconfined).getOrThrow()
        assertThat(dir.name).isEqualTo("vits-piper-en_US-ljspeech-medium")
        assertThat(File(dir, "model.onnx").length()).isEqualTo(10)
        assertThat(File(dir, "espeak-ng-data/en_dict").isFile).isTrue()
        assertThat(File(dir, "manifest.json").isFile).isTrue()
        assertThat(zip.exists()).isFalse()
        assertThat(File(dir.parentFile, "${dir.name}.part").exists()).isFalse()
        assertThat(VoiceUnpacker.manifest(dir)!!.map { it.name }).containsExactly("model.onnx", "tokens.txt", "espeak-ng-data/en_dict").inOrder()
    }

    @Test fun refusesAZipSlipAndAZipWithoutAManifestAndLeavesNothingBehind() = runTest {
        val voices = tmp.newFolder("v2")
        val slip = File(voices, "evil.zip")
        ZipOutputStream(slip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../escaped.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertThat(VoiceUnpacker.unpack(slip, Dispatchers.Unconfined).isFailure).isTrue()
        assertThat(File(voices, "evil").exists()).isFalse()
        assertThat(File(voices, "evil.part").exists()).isFalse()
        assertThat(File(tmp.root, "escaped.txt").exists()).isFalse()
        assertThat(slip.exists()).isTrue()                           // a refused zip is kept for Readiness to report

        val plain = File(voices, "plain.zip")
        ZipOutputStream(plain.outputStream()).use { z -> z.putNextEntry(ZipEntry("model.onnx")); z.write(ByteArray(2)); z.closeEntry() }
        assertThat(VoiceUnpacker.unpack(plain, Dispatchers.Unconfined).isFailure).isTrue()
    }

    @Test fun integrityChecksTheUnpackedFilesAgainstTheManifest() = runTest {
        val model = ByteArray(10) { 2 }
        val zip = zip(files = mapOf("model.onnx" to model, "tokens.txt" to "t".toByteArray()))
        val record = record(zip)
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.MISSING)             // not unpacked yet
        val dir = VoiceUnpacker.unpack(zip, Dispatchers.Unconfined).getOrThrow()
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.SIZE_OK)
        assertThat(IntegrityChecker.quick(record(zip, verifiedAt = 1L))).isEqualTo(Integrity.VERIFIED)
        assertThat(IntegrityChecker.full(record, Dispatchers.Unconfined)).isEqualTo(Integrity.VERIFIED)
        File(dir, "model.onnx").writeBytes(ByteArray(10) { 3 })                              // same size, other bytes
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.SIZE_OK)
        assertThat(IntegrityChecker.full(record, Dispatchers.Unconfined)).isEqualTo(Integrity.CORRUPTED)
        File(dir, "tokens.txt").writeText("longer")
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.INCOMPLETE)
        File(dir, "tokens.txt").delete()
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.MISSING)
        File(dir, "manifest.json").writeText("junk")
        assertThat(IntegrityChecker.quick(record)).isEqualTo(Integrity.CORRUPTED)
    }
}
