package app.granger.core.vectors

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Where a vector came from: an article path or a chapter href, and the chunk's ordinal there. */
data class ChunkKey(val key: String, val ordinal: Int)

/**
 * A pack's or book's precomputed vectors (`tools/vectors/gvec.py` writes them; the layout is documented there), mapped
 * read-only. Vectors are int8 with one float scale each; each row also carries its chunk text's hash
 * ([app.granger.core.text.Chunkers.hash]) and its [ChunkKey]. A version 2 file (`tools/vectors/ivf.py`, Plan SQ) keeps its
 * rows grouped into lists around [listCount] centroids, so a search can read only the nearest lists ([VectorSearch]).
 */
class VectorFile private constructor(val file: File, private val channel: FileChannel, private val buf: ByteBuffer, val header: Header) :
    Closeable {

    data class Header(
        val version: Int,
        val chunkerVersion: Int,
        val dims: Int,
        val count: Int,
        val embedderSha256: String,
        val sourceSha256: String,
    )

    private val scalesAt = HEADER + header.count.toLong() * header.dims
    private val hashesAt = scalesAt + 4L * header.count
    private val keysAt = buf.getLong(80)
    private val blobAt = keysAt + 4L * header.count + 4

    fun scale(row: Int): Float = buf.getFloat((scalesAt + 4L * row).toInt())

    fun hash(row: Int): String = buildString(16) {
        val at = (hashesAt + 8L * row).toInt()
        for (i in 0 until 8) append("%02x".format(buf.get(at + i)))
    }

    fun key(row: Int): ChunkKey {
        val at = (blobAt + (buf.getInt((keysAt + 4L * row).toInt()).toLong() and 0xFFFFFFFFL)).toInt()
        val ordinal = buf.getInt(at)
        val length = buf.getShort(at + 4).toInt() and 0xFFFF
        val bytes = ByteArray(length)
        buf.duplicate().apply { position(at + 6) }.get(bytes)
        return ChunkKey(bytes.toString(Charsets.UTF_8), ordinal)
    }

    private val listsAt: Long = if (header.version >= 2) buf.getLong(LISTS_AT) else -1L

    /** How many lists the rows are grouped in; 0 for a version 1 file, read whole. */
    val listCount: Int = if (listsAt < 0) 0 else buf.getInt(listsAt.toInt())

    private val centroidsAt = listsAt + 4
    private val centroidScalesAt = centroidsAt + listCount.toLong() * header.dims
    private val startsAt = centroidScalesAt + 4L * listCount

    /** The first row of list [list]; `listStart(listCount)` is [Header.count]. */
    fun listStart(list: Int): Int = buf.getInt((startsAt + 4L * list).toInt())

    fun centroidScale(list: Int): Float = buf.getFloat((centroidScalesAt + 4L * list).toInt())

    /** Copies every centroid (listCount × dims int8) into [dst]. */
    internal fun copyCentroids(dst: ByteArray) {
        buf.duplicate().apply { position(centroidsAt.toInt()) }.get(dst, 0, listCount * header.dims)
    }

    /** Copies [rows] vectors starting at [from] into [dst] (rows × dims bytes). */
    internal fun copyRows(from: Int, rows: Int, dst: ByteArray) {
        buf.duplicate().apply { position(HEADER + from * header.dims) }.get(dst, 0, rows * header.dims)
    }

    override fun close() = channel.close()

    companion object {
        const val VERSION = 1
        /** The newest version read: 2 adds the lists ([listCount]). */
        const val MAX_VERSION = 2
        private const val HEADER = 128
        private const val LISTS_AT = 88
        private val MAGIC = "GVEC".toByteArray()

        /** Null for a file that isn't a version 1 or 2 vector file, or is truncated. */
        fun open(file: File): VectorFile? {
            val channel = try {
                RandomAccessFile(file, "r").channel
            } catch (_: IOException) {
                return null
            }
            return try {
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
                if (buf.capacity() < HEADER || (0 until 4).any { buf.get(it) != MAGIC[it] }) return null.also { channel.close() }
                val header = Header(
                    version = buf.getShort(4).toInt() and 0xFFFF,
                    chunkerVersion = buf.getShort(6).toInt() and 0xFFFF,
                    dims = buf.getInt(8),
                    count = buf.getInt(12),
                    embedderSha256 = hex(buf, 16),
                    sourceSha256 = hex(buf, 48),
                )
                val keysAt = buf.getLong(80)
                val blobLengthAt = keysAt + 4L * header.count
                val size = buf.capacity().toLong()
                // Version 1 ends with the key blob; version 2 has the lists section after it (its offset at 88).
                val blobEnd = if (header.version >= 2) buf.getLong(LISTS_AT) else size
                val fits = header.version in VERSION..MAX_VERSION && header.dims > 0 && header.count >= 0 &&
                    keysAt == HEADER + header.count.toLong() * (header.dims + 12) && blobLengthAt + 4 <= size &&
                    blobLengthAt + 4 + (buf.getInt(blobLengthAt.toInt()).toLong() and 0xFFFFFFFFL) == blobEnd &&
                    (header.version < 2 || listsFit(buf, blobEnd, header.dims, header.count, size))
                if (fits) VectorFile(file, channel, buf, header) else null.also { channel.close() }
            } catch (_: IOException) {
                channel.close()
                null
            }
        }

        /** A version 2 file's lists section: n lists of centroids and scales, then n + 1 starts from 0 to count, ending the file. */
        private fun listsFit(buf: ByteBuffer, at: Long, dims: Int, count: Int, size: Long): Boolean {
            if (at + 4 > size) return false
            val n = buf.getInt(at.toInt())
            if (n <= 0) return false
            val startsAt = at + 4 + n.toLong() * (dims + 4)
            if (startsAt + 4L * (n + 1) != size) return false
            return buf.getInt(startsAt.toInt()) == 0 && buf.getInt((startsAt + 4L * n).toInt()) == count
        }

        /**
         * Writes [vectors] (each truncated to [dims], renormalized and quantized, as `gvec.py` does) with their chunk
         * [hashes] (16 hex digits) and [keys]. Used on the phone for books a user adds, and by tests.
         */
        fun write(
            file: File,
            vectors: List<FloatArray>,
            dims: Int,
            hashes: List<String>,
            keys: List<ChunkKey>,
            chunkerVersion: Int,
            embedderSha256: String,
            sourceSha256: String,
        ) {
            require(vectors.size == hashes.size && vectors.size == keys.size) { "vectors, hashes and keys differ in count" }
            val blob = java.io.ByteArrayOutputStream()
            val offsets = keys.map { k ->
                val at = blob.size()
                val bytes = k.key.toByteArray(Charsets.UTF_8)
                blob.write(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(k.ordinal).putShort(bytes.size.toShort()).array())
                blob.write(bytes)
                at
            }
            val count = vectors.size
            val keysAt = HEADER.toLong() + count.toLong() * (dims + 12)
            val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            head.put(MAGIC).putShort(VERSION.toShort()).putShort(chunkerVersion.toShort()).putInt(dims).putInt(count)
            head.put(unhex(embedderSha256, 32)).put(unhex(sourceSha256, 32)).putLong(keysAt)
            val quantized = vectors.map { VectorSearch.quantize(it, dims) }
            val body = ByteBuffer.allocate(count * (dims + 12)).order(ByteOrder.LITTLE_ENDIAN)
            quantized.forEach { body.put(it.first) }
            quantized.forEach { body.putFloat(it.second) }
            hashes.forEach { body.put(unhex(it, 8)) }
            val tail = ByteBuffer.allocate(4 * count + 4).order(ByteOrder.LITTLE_ENDIAN)
            offsets.forEach { tail.putInt(it) }
            tail.putInt(blob.size())
            val tmp = File(file.path + ".part")
            tmp.outputStream().use { out ->
                out.write(head.array())
                out.write(body.array())
                out.write(tail.array())
                blob.writeTo(out)
            }
            if (!tmp.renameTo(file)) throw IOException("could not move ${tmp.name} into place")
        }

        private fun unhex(hex: String, bytes: Int): ByteArray {
            require(hex.length == 2 * bytes) { "expected ${2 * bytes} hex digits" }
            return ByteArray(bytes) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }

        private fun hex(buf: ByteBuffer, at: Int) = buildString(64) { for (i in 0 until 32) append("%02x".format(buf.get(at + i))) }
    }
}
