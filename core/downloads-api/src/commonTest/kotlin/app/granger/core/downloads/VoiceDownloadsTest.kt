package app.granger.core.downloads

import app.granger.core.catalog.VoiceEntry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

/** A voice through the shared queue: where it goes, its manifest, and Readiness' checks of the unpacked folder. */
class VoiceDownloadsTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"     // sha256("abc")

    @Test fun aVoiceZipGoesToTheVoicesFolderAndUnpacksBesideIt() {
        assertEquals("voices/vits-piper-en_GB-granger_owl-medium.zip", VoiceDownloads.zipPath("vits-piper-en_GB-granger_owl-medium"))
        assertEquals("/v/voices/x", VoiceDownloads.voiceDir("/v/voices/x.zip"))
        assertEquals("/v/voices/x.part", VoiceDownloads.partDir("/v/voices/x.zip"))
        assertTrue(VoiceDownloads.isSafeEntry("espeak-ng-data/en_dict"))
        assertFalse(VoiceDownloads.isSafeEntry("../evil"))
        assertFalse(VoiceDownloads.isSafeEntry("a/../../evil"))
        assertFalse(VoiceDownloads.isSafeEntry("/etc/passwd"))
    }

    @Test fun theQueueSpecPutsTheZipUnderVoices() {
        val entry = VoiceEntry(
            id = "vits-piper-en_US-ljspeech-medium", title = "Granger voice", engine = "piper", language = "en-US",
            sizeBytes = 3, unpackedBytes = 9, sha256 = abc, url = "https://x/v.zip", license = "Public domain",
        )
        val spec = downloadSpecOf(entry)
        assertEquals(ItemKind.VOICE, spec.kind)
        assertEquals("voices/vits-piper-en_US-ljspeech-medium.zip", spec.relativePath)
    }

    @Test fun manifestsParseAndBadOnesDont() {
        val files = VoiceDownloads.parseManifest("""{"id":"x","engine":"piper","files":[{"name":"a.onnx","sizeBytes":3,"sha256":"$abc"}]}""")
        assertEquals(listOf(VoiceFile("a.onnx", 3, abc)), files)
        assertNull(VoiceDownloads.parseManifest("""{"id":"x"}"""))
        assertNull(VoiceDownloads.parseManifest("not json"))
    }

    @Test fun readinessChecksTheUnpackedFolder() = runTest {
        val volume = Path(SystemTemporaryDirectory, "voice-downloads-${Random.nextLong().toULong()}")
        val voices = Path(volume, "voices")
        val zip = Path(voices, "v.zip").toString()
        val dir = Path(voices, "v")
        fun write(p: Path, text: String) { SystemFileSystem.createDirectories(p.parent!!); SystemFileSystem.sink(p).buffered().use { it.writeString(text) } }
        fun record(verifiedAt: Long? = null) = DownloadRecord(
            itemId = "v", hash = "0".repeat(64), kind = ItemKind.VOICE, title = "Granger voice", urls = listOf("https://x/v.zip"),
            sizeBytes = 3, filePath = zip, snapshotDate = "", wifiOnly = true, state = DownloadState.INSTALLED,
            verifiedAt = verifiedAt, createdAt = 0L, updatedAt = 0L,
        )
        try {
            assertEquals(Integrity.MISSING, Integrity.quick(record()))
            SystemFileSystem.createDirectories(dir)
            assertEquals(Integrity.CORRUPTED, Integrity.quick(record()))           // no manifest
            write(Path(dir, "manifest.json"), """{"files":[{"name":"model.onnx","sizeBytes":3,"sha256":"$abc"},{"name":"espeak-ng-data/en_dict","sizeBytes":3,"sha256":"$abc"}]}""")
            write(Path(dir, "model.onnx"), "abc")
            assertEquals(Integrity.MISSING, Integrity.quick(record()))
            write(Path(dir, "espeak-ng-data/en_dict"), "ab")
            assertEquals(Integrity.INCOMPLETE, Integrity.quick(record()))
            write(Path(dir, "espeak-ng-data/en_dict"), "abc")
            assertEquals(Integrity.SIZE_OK, Integrity.quick(record()))
            assertEquals(Integrity.VERIFIED, Integrity.quick(record(verifiedAt = 1L)))
            val progress = mutableListOf<Float>()
            assertEquals(Integrity.VERIFIED, VoiceDownloads.full(record(), Dispatchers.Unconfined) { progress += it })
            assertEquals(1f, progress.last())
            write(Path(dir, "model.onnx"), "abd")
            assertEquals(Integrity.CORRUPTED, VoiceDownloads.full(record(), Dispatchers.Unconfined))
        } finally {
            deleteTree(volume)
        }
    }

    private fun deleteTree(p: Path) {
        if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach { deleteTree(it) }
        SystemFileSystem.delete(p, mustExist = false)
    }
}
