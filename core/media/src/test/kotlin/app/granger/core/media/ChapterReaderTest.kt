package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    private fun read(name: String): List<Chapter> = read(fixture(name))

    private fun read(file: File): List<Chapter> = RandomAccessFile(file, "r").use { ChapterReader.read(it) }

    private fun read(bytes: ByteArray): List<Chapter> = read(tmp.newFile().apply { writeBytes(bytes) })

    @Test fun readsM4bChapters() {
        val chapters = read("chapters.m4b")
        assertThat(chapters.map { it.title }).containsExactly("One", "Two", "Three").inOrder()
        assertThat(chapters.map { it.startMs }).containsExactly(0L, 1000L, 2000L).inOrder()
    }

    @Test fun readsQuickTimeChapterTrackWhenNoChpl() {
        val bytes = fixture("chapters.m4b").readBytes()
        val at = indexOf(bytes, "chpl".toByteArray())
        assertThat(at).isAtLeast(0)
        "free".toByteArray().copyInto(bytes, at)
        val chapters = read(bytes)
        assertThat(chapters).containsExactly(Chapter("One", 0), Chapter("Two", 1000), Chapter("Three", 2000)).inOrder()
    }

    @Test fun readsMp3ChapFrames() {
        val chapters = read("chapters.mp3")
        assertThat(chapters).hasSize(3)
        assertThat(chapters[1].startMs).isGreaterThan(0)
        assertThat(chapters).containsExactly(Chapter("One", 0), Chapter("Two", 1000), Chapter("Three", 2000)).inOrder()
    }

    @Test fun noChaptersIsEmpty() {
        assertThat(read("tagged.mp3")).isEmpty()
        assertThat(read("tagged.m4a")).isEmpty()
        assertThat(read("untagged.mp3")).isEmpty()
        assertThat(read("tagged.flac")).isEmpty()
    }

    @Test fun brokenFileIsEmptyNotThrown() {
        assertThat(read("broken.mp3")).isEmpty()
        assertThat(read(ByteArray(0))).isEmpty()
    }

    @Test fun readsId3v23ChapFramesWithPlainSizes() {
        // A 300-byte frame first: its size bytes are 00 00 01 2C, which differ when read as syncsafe.
        val tag = id3(3, frame(3, "TXXX", ByteArray(300)) + chap(3, "c1", 0, "Intro") + chap(3, "c2", 90_000, "Part 2"))
        assertThat(read(tag + audio())).containsExactly(Chapter("Intro", 0), Chapter("Part 2", 90_000)).inOrder()
    }

    @Test fun readsUnsynchronisedId3v23Tag() {
        // Start 65280 ms is 00 00 FF 00 and the unused offsets are FF FF FF FF, so the tag really needs decoding.
        val frames = chap(3, "c1", 0, "One") + chap(3, "c2", 65_280, "Two")
        val tag = id3(3, unsync(frames), flags = 0x80)
        assertThat(read(tag + audio())).containsExactly(Chapter("One", 0), Chapter("Two", 65_280)).inOrder()
    }

    @Test fun readsUnsynchronisedId3v24Frame() {
        val body = chapBody(4, "c1", 65_280, "Two")
        // Frame flags: unsynchronisation (0x02) plus the data length indicator (0x01) that v2.4 requires with it.
        val frame = frame(4, "CHAP", syncsafe(body.size) + unsync(body), flags = 0x03)
        val tag = id3(4, chap(4, "c0", 0, "One") + frame)
        assertThat(read(tag + audio())).containsExactly(Chapter("One", 0), Chapter("Two", 65_280)).inOrder()
    }

    @Test fun ordersChaptersByStartTimeAndDecodesUtf16() {
        val utf16 = byteArrayOf(1) + "Zwei".toByteArray(Charsets.UTF_16) // with BOM
        val chap2 = frame(4, "CHAP", "c2".toByteArray() + byteArrayOf(0) + int(5000) + int(6000) + int(-1) + int(-1) +
            frame(4, "TIT2", utf16))
        val tag = id3(4, chap2 + chap(4, "c1", 0, "Eins"))
        assertThat(read(tag + audio())).containsExactly(Chapter("Eins", 0), Chapter("Zwei", 5000)).inOrder()
    }

    @Test fun truncatedFilesNeverThrow() {
        for (name in listOf("chapters.mp3", "chapters.m4b")) {
            val bytes = fixture(name).readBytes()
            for (cut in 0..bytes.size step 13) read(bytes.copyOf(cut))
        }
        // A CHAP frame cut inside its title still yields the earlier chapter.
        val tag = id3(4, chap(4, "c1", 0, "One") + chap(4, "c2", 1000, "Two"))
        assertThat(read(tag.copyOf(tag.size - 3))).contains(Chapter("One", 0))
    }

    @Test fun hugeDeclaredSizesAreNotTrusted() {
        // ID3 header claiming a 256 MB tag in a tiny file.
        assertThat(read(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F) + chap(4, "c1", 0, "One")))
            .containsExactly(Chapter("One", 0))
        // MP4: a moov whose 64-bit size is enormous, holding a chpl that claims to run far past the end.
        // Both are cut at the end of their parent, so the real entry is still read.
        val chpl = box("chpl", byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 1) + ByteArray(8) + byteArrayOf(3) + "One".toByteArray())
        chpl[0] = 0x7F
        val moov = int(1) + "moov".toByteArray() + long(Long.MAX_VALUE) + box("udta", chpl)
        assertThat(read(ftyp() + moov)).containsExactly(Chapter("One", 0))
        // A chapter track whose stts/stsz/stco claim billions of entries that aren't there.
        val stbl = box("stbl", box("stts", int(0) + int(Int.MAX_VALUE) + int(-1) + int(1)) +
            box("stsz", int(0) + int(0) + int(-1)) + box("stco", int(0) + int(-1) + int(0)) +
            box("stsc", int(0) + int(1) + int(1) + int(-1) + int(1)))
        val trak = box("trak", box("tkhd", ByteArray(12) + int(1)) + box("tref", box("chap", int(1))) +
            box("mdia", box("mdhd", ByteArray(12) + int(1000)) + box("minf", stbl)))
        assertThat(read(ftyp() + box("moov", trak))).isEmpty()
    }

    // --- builders ---

    private fun audio() = ByteArray(64)

    private fun ftyp() = box("ftyp", "M4A ".toByteArray() + int(0))

    private fun id3(major: Int, frames: ByteArray, flags: Int = 0) =
        byteArrayOf(0x49, 0x44, 0x33, major.toByte(), 0, flags.toByte()) + syncsafe(frames.size) + frames

    private fun frame(major: Int, id: String, body: ByteArray, flags: Int = 0) =
        id.toByteArray() + (if (major == 4) syncsafe(body.size) else int(body.size)) + byteArrayOf(0, flags.toByte()) + body

    private fun chapBody(major: Int, id: String, startMs: Int, title: String) =
        id.toByteArray() + byteArrayOf(0) + int(startMs) + int(startMs + 1000) + int(-1) + int(-1) +
            frame(major, "TIT2", byteArrayOf(3) + title.toByteArray())

    private fun chap(major: Int, id: String, startMs: Int, title: String) = frame(major, "CHAP", chapBody(major, id, startMs, title))

    private fun box(type: String, body: ByteArray) = int(8 + body.size) + type.toByteArray() + body

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun long(v: Long) = int((v ushr 32).toInt()) + int(v.toInt())

    private fun syncsafe(v: Int) =
        byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun unsync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in data) {
            out.write(b.toInt())
            if (b == 0xFF.toByte()) out.write(0)
        }
        return out.toByteArray()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
