package app.granger.core.media

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** One chapter: its title ("" if the file has none) and start time. Durations follow from the next start. */
data class Chapter(val title: String, val startMs: Long)

/**
 * Reads chapter lists without any native code: MP4 (Nero `chpl`, else the QuickTime chapter track
 * referenced by `tref/chap`) and ID3v2.3/2.4 `CHAP` frames.
 *
 * Defensive by design: it never throws, never reads past the end of the file, and never trusts a
 * declared size for an allocation (every read is capped). A malformed file gives the chapters
 * parsed before the damage, or an empty list. It only reads [RandomAccessFile]; the caller opens it
 * read-only and closes it.
 */
object ChapterReader {

    private const val MAX_CHAPTERS = 10_000
    /** A v2.3 tag with tag-wide unsynchronisation must be decoded in memory; larger tags are cut here. */
    private const val MAX_UNSYNC_TAG_BYTES = 16 shl 20
    /** Of a CHAP frame only the start is read: the title subframe comes before any chapter image in practice. */
    private const val MAX_FRAME_BYTES = 64 shl 10
    private const val MAX_BOX_BYTES = 4 shl 20
    private const val MAX_TEXT_SAMPLE_BYTES = 4 shl 10
    private const val MAX_BOXES_PER_LEVEL = 100_000

    fun read(file: RandomAccessFile): List<Chapter> = read { FileSource(file) }

    /**
     * The same, from a channel (e.g. `FileInputStream(pfd.fileDescriptor).channel` for a file opened read-only through
     * a content resolver). Reads are positional, so the channel's position is neither used nor changed.
     */
    fun read(channel: FileChannel): List<Chapter> = read { ChannelSource(channel) }

    private fun read(open: () -> Source): List<Chapter> = try {
        val src = open()
        val head = src.read(0, 8)
        when {
            head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() ->
                Id3.read(src)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> Mp4.read(src)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide")

    // --- byte sources ---

    private interface Source {
        val length: Long
        /** Up to [n] bytes from [pos]; fewer at the end of the data, none outside it. */
        fun read(pos: Long, n: Int): ByteArray
    }

    private class FileSource(private val file: RandomAccessFile) : Source {
        override val length = file.length()
        override fun read(pos: Long, n: Int): ByteArray {
            if (pos < 0 || pos >= length || n <= 0) return ByteArray(0)
            val out = ByteArray(minOf(n.toLong(), length - pos).toInt())
            file.seek(pos)
            file.readFully(out)
            return out
        }
    }

    private class ChannelSource(private val channel: FileChannel) : Source {
        override val length = channel.size()
        override fun read(pos: Long, n: Int): ByteArray {
            if (pos < 0 || pos >= length || n <= 0) return ByteArray(0)
            val out = ByteBuffer.allocate(minOf(n.toLong(), length - pos).toInt())
            while (out.hasRemaining()) {
                if (channel.read(out, pos + out.position()) < 0) break
            }
            return if (out.hasRemaining()) out.array().copyOf(out.position()) else out.array()
        }
    }

    private class ArraySource(private val bytes: ByteArray) : Source {
        override val length = bytes.size.toLong()
        override fun read(pos: Long, n: Int): ByteArray {
            if (pos < 0 || pos >= length || n <= 0) return ByteArray(0)
            return bytes.copyOfRange(pos.toInt(), minOf(n.toLong(), length - pos).toInt() + pos.toInt())
        }
    }

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
    private fun ByteArray.u32(i: Int): Long = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
    private fun ByteArray.u64(i: Int): Long = (u32(i) shl 32) or u32(i + 4)
    private fun ByteArray.text(from: Int, n: Int): String = String(this, from, n, Charsets.ISO_8859_1)

    // --- ID3v2 ---

    private object Id3 {
        private class Frame(val id: String, val body: ByteArray)

        fun read(file: Source): List<Chapter> {
            val h = file.read(0, 10)
            if (h.size < 10) return emptyList()
            val major = h.u8(3)
            if (major != 3 && major != 4) return emptyList() // v2.2 has no CHAP frames
            val flags = h.u8(5)
            val tagSize = syncsafe(h, 6)
            var src = file
            var pos = 10L
            var end = minOf(10 + tagSize, file.length)
            val tagUnsync = flags and 0x80 != 0
            if (tagUnsync && major == 3) {
                src = ArraySource(deunsync(file.read(10, minOf(tagSize, MAX_UNSYNC_TAG_BYTES.toLong()).toInt())))
                pos = 0
                end = src.length
            }
            if (flags and 0x40 != 0) {
                val ext = src.read(pos, 4)
                if (ext.size < 4) return emptyList()
                pos += if (major == 3) 4 + ext.u32(0) else syncsafe(ext, 0)
            }
            val chapters = mutableListOf<Chapter>()
            for (f in frames(src, pos, end, major, tagUnsync && major == 4) { it == "CHAP" }) {
                chap(f.body, major)?.let(chapters::add)
                if (chapters.size >= MAX_CHAPTERS) break
            }
            return chapters.sortedBy { it.startMs }
        }

        /** Element id, start/end ms, start/end byte offsets, then subframes. */
        private fun chap(b: ByteArray, major: Int): Chapter? {
            val nul = b.indexOf(0)
            if (nul < 0 || nul + 17 > b.size) return null
            val start = b.u32(nul + 1)
            val sub = ArraySource(b)
            val title = frames(sub, nul + 17L, sub.length, major, false) { it == "TIT2" }.firstOrNull()?.let { text(it.body) }
            return Chapter(title ?: "", start)
        }

        /**
         * The frames between [from] and [end], with bodies read (capped) only for ids [wanted] accepts.
         * Stops at padding or at the first frame header that makes no sense. Frames cut short by the end
         * of the data are dropped.
         */
        private fun frames(src: Source, from: Long, end: Long, major: Int, unsyncAll: Boolean, wanted: (String) -> Boolean) =
            sequence {
                var pos = from
                while (pos + 10 <= end) {
                    val h = src.read(pos, 10)
                    if (h.size < 10) break
                    val id = h.text(0, 4)
                    if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break // padding or garbage
                    val size = if (major == 4 && (4..7).all { h.u8(it) < 0x80 }) syncsafe(h, 4) else h.u32(4)
                    val bodyStart = pos + 10
                    if (wanted(id)) {
                        val want = minOf(size, end - bodyStart, MAX_FRAME_BYTES.toLong()).toInt()
                        val raw = src.read(bodyStart, want)
                        val complete = raw.size.toLong() == minOf(size, MAX_FRAME_BYTES.toLong())
                        if (complete) decode(raw, major, h.u8(9), unsyncAll)?.let { yield(Frame(id, it)) }
                    }
                    pos = bodyStart + size
                }
            }

        /** Undoes the per-frame format flags; null for compressed or encrypted frames. */
        private fun decode(raw: ByteArray, major: Int, format: Int, unsyncAll: Boolean): ByteArray? {
            var skip = 0
            var unsync = false
            if (major == 4) {
                if (format and 0x0C != 0) return null
                if (format and 0x40 != 0) skip += 1 // group id
                if (format and 0x01 != 0) skip += 4 // data length indicator
                unsync = unsyncAll || format and 0x02 != 0
            } else {
                if (format and 0xC0 != 0) return null
                if (format and 0x20 != 0) skip += 1
            }
            if (skip > raw.size) return null
            val body = raw.copyOfRange(skip, raw.size)
            return if (unsync) deunsync(body) else body
        }

        /** A text frame's first value. */
        private fun text(b: ByteArray): String {
            if (b.isEmpty()) return ""
            val charset = when (b[0].toInt()) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            return String(b, 1, b.size - 1, charset).substringBefore('\u0000').trim()
        }

        private fun syncsafe(b: ByteArray, i: Int): Long =
            ((b.u8(i) and 0x7F).toLong() shl 21) or ((b.u8(i + 1) and 0x7F).toLong() shl 14) or
                ((b.u8(i + 2) and 0x7F).toLong() shl 7) or (b.u8(i + 3) and 0x7F).toLong()

        /** Drops the 0x00 inserted after every 0xFF. */
        private fun deunsync(b: ByteArray): ByteArray {
            val out = ByteArray(b.size)
            var n = 0
            var i = 0
            while (i < b.size) {
                out[n++] = b[i]
                if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
                i++
            }
            return out.copyOf(n)
        }
    }

    // --- MP4 ---

    private object Mp4 {
        private class Box(val type: String, val bodyStart: Long, val end: Long) {
            val bodySize get() = end - bodyStart
        }

        fun read(src: Source): List<Chapter> {
            val moov = boxes(src, 0, src.length).firstOrNull { it.type == "moov" } ?: return emptyList()
            val children = boxes(src, moov.bodyStart, moov.end)
            val nero = children.firstOrNull { it.type == "udta" }
                ?.let { udta -> boxes(src, udta.bodyStart, udta.end).firstOrNull { it.type == "chpl" } }
                ?.let { chpl(body(src, it)) }
                .orEmpty()
            if (nero.isNotEmpty()) return nero
            return chapterTrack(src, children.filter { it.type == "trak" })
        }

        /** The boxes directly between [from] and [end]. A box declared past [end] is cut at [end]. */
        private fun boxes(src: Source, from: Long, end: Long): List<Box> {
            val out = mutableListOf<Box>()
            var pos = from
            while (pos + 8 <= end && out.size < MAX_BOXES_PER_LEVEL) {
                val h = src.read(pos, 16)
                if (h.size < 8) break
                var header = 8L
                var size = h.u32(0)
                if (size == 1L) {
                    if (h.size < 16) break
                    size = h.u64(8)
                    header = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header) break // also catches a negative 64-bit size
                val boxEnd = if (size > end - pos) end else pos + size
                out += Box(h.text(4, 4), minOf(pos + header, boxEnd), boxEnd)
                pos = boxEnd
            }
            return out
        }

        private fun child(src: Source, parent: Box?, vararg path: String): Box? =
            path.fold(parent) { box, type -> box?.let { b -> boxes(src, b.bodyStart, b.end).firstOrNull { it.type == type } } }

        private fun body(src: Source, box: Box): ByteArray = src.read(box.bodyStart, minOf(box.bodySize, MAX_BOX_BYTES.toLong()).toInt())

        /**
         * Nero chapters: version, flags, then (version 1) five bytes holding the count. ffmpeg writes 4 reserved
         * bytes and a 1-byte count, mp4v2 1 reserved byte and a 4-byte count; reading the last four as a u32
         * fits both. Entries: start (u64, 100 ns units), title length (u8), UTF-8 title.
         */
        private fun chpl(b: ByteArray): List<Chapter> {
            if (b.size < 5) return emptyList()
            val count: Long
            var p: Int
            if (b.u8(0) == 1) {
                if (b.size < 9) return emptyList()
                count = b.u32(5)
                p = 9
            } else {
                count = b.u8(4).toLong()
                p = 5
            }
            val out = mutableListOf<Chapter>()
            while (out.size < minOf(count, MAX_CHAPTERS.toLong()) && p + 9 <= b.size) {
                val start = b.u64(p)
                val len = minOf(b.u8(p + 8), b.size - p - 9)
                val title = String(b, p + 9, len, Charsets.UTF_8).trim()
                if (start >= 0) out += Chapter(title, start / 10_000)
                p += 9 + len
            }
            return out.sortedBy { it.startMs }
        }

        /** The QuickTime chapter track: a text track named by the audio track's `tref/chap`. */
        private fun chapterTrack(src: Source, traks: List<Box>): List<Chapter> {
            val ids = traks.flatMap { trak ->
                child(src, trak, "tref", "chap")?.let { chap ->
                    val b = body(src, chap)
                    (0 until b.size / 4).map { b.u32(it * 4) }
                }.orEmpty()
            }
            val track = traks.firstOrNull { trackId(src, it) in ids } ?: return emptyList()
            val mdia = child(src, track, "mdia") ?: return emptyList()
            val timescale = child(src, mdia, "mdhd")?.let { body(src, it) }?.let { b ->
                val at = if (b.isNotEmpty() && b.u8(0) == 1) 20 else 12
                if (b.size >= at + 4) b.u32(at) else null
            }?.takeIf { it > 0 } ?: return emptyList()
            val stbl = child(src, mdia, "minf", "stbl") ?: return emptyList()
            fun table(type: String) = child(src, stbl, type)?.let { body(src, it) }

            val times = sampleTimes(table("stts") ?: return emptyList())
            val sizes = sampleSizes(table("stsz") ?: return emptyList())
            val chunkOffsets = table("stco")?.let { offsets(it, 4) } ?: table("co64")?.let { offsets(it, 8) } ?: return emptyList()
            val sampleOffsets = sampleOffsets(table("stsc") ?: return emptyList(), chunkOffsets, sizes)

            val n = minOf(times.size, sampleOffsets.size)
            return (0 until n).map { i ->
                val sample = src.read(sampleOffsets[i], minOf(sizes[i], MAX_TEXT_SAMPLE_BYTES.toLong()).toInt())
                Chapter(textSample(sample), times[i] * 1000 / timescale)
            }.sortedBy { it.startMs }
        }

        private fun trackId(src: Source, trak: Box): Long? = child(src, trak, "tkhd")?.let { body(src, it) }?.let { b ->
            val at = if (b.isNotEmpty() && b.u8(0) == 1) 20 else 12
            if (b.size >= at + 4) b.u32(at) else null
        }

        /** Number of whole entries of [entrySize] bytes after a [header]-byte prefix, capped by the declared count. */
        private fun entries(b: ByteArray, header: Int, declared: Long, entrySize: Int): Int =
            if (b.size < header) 0 else minOf(declared, ((b.size - header) / entrySize).toLong()).toInt()

        /** stts: version/flags, count, then (sample count, delta) pairs. Each sample's start in timescale units. */
        private fun sampleTimes(b: ByteArray): List<Long> {
            if (b.size < 8) return emptyList()
            val out = mutableListOf<Long>()
            var t = 0L
            for (e in 0 until entries(b, 8, b.u32(4), 8)) {
                val count = b.u32(8 + e * 8)
                val delta = b.u32(12 + e * 8)
                var j = 0L
                while (j < count && out.size < MAX_CHAPTERS) {
                    out += t
                    t += delta
                    j++
                }
                if (out.size >= MAX_CHAPTERS) break
            }
            return out
        }

        /** stsz: version/flags, fixed size (0 = per-sample table), count, table. */
        private fun sampleSizes(b: ByteArray): List<Long> {
            if (b.size < 12) return emptyList()
            val fixed = b.u32(4)
            val count = minOf(b.u32(8), MAX_CHAPTERS.toLong())
            if (fixed != 0L) return List(count.toInt()) { fixed }
            return List(entries(b, 12, count, 4)) { b.u32(12 + it * 4) }
        }

        /** stco / co64: version/flags, count, offsets. */
        private fun offsets(b: ByteArray, width: Int): List<Long> {
            if (b.size < 8) return emptyList()
            val n = entries(b, 8, minOf(b.u32(4), MAX_CHAPTERS.toLong()), width)
            return List(n) { if (width == 4) b.u32(8 + it * 4) else b.u64(8 + it * 8) }
        }

        /** stsc maps chunks to samples-per-chunk; samples sit back to back inside each chunk. */
        private fun sampleOffsets(stsc: ByteArray, chunkOffsets: List<Long>, sizes: List<Long>): List<Long> {
            if (stsc.size < 8) return emptyList()
            val runs = List(entries(stsc, 8, minOf(stsc.u32(4), MAX_CHAPTERS.toLong()), 12)) {
                stsc.u32(8 + it * 12) to stsc.u32(12 + it * 12)
            }
            val out = mutableListOf<Long>()
            var run = -1
            for ((index, chunkOffset) in chunkOffsets.withIndex()) {
                val chunk = index + 1L
                while (run + 1 < runs.size && runs[run + 1].first <= chunk) run++
                val perChunk = runs.getOrNull(run)?.second ?: return out
                var offset = chunkOffset
                var k = 0L
                while (k < perChunk && out.size < sizes.size) {
                    out += offset
                    offset += sizes[out.size - 1]
                    k++
                }
                if (out.size >= sizes.size) break
            }
            return out
        }

        /** A QuickTime text sample: u16 length, then UTF-8, or UTF-16 with a BOM. */
        private fun textSample(b: ByteArray): String {
            if (b.size < 2) return ""
            val len = minOf(b.u16(0), b.size - 2)
            val utf16 = len >= 2 && b.u8(2) == 0xFE && b.u8(3) == 0xFF
            return String(b, 2, len, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).trim()
        }
    }
}
