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
 * ([app.granger.core.text.Chunkers.hash]) and its [ChunkKey].
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

    /** Copies [rows] vectors starting at [from] into [dst] (rows × dims bytes). */
    internal fun copyRows(from: Int, rows: Int, dst: ByteArray) {
        buf.duplicate().apply { position(HEADER + from * header.dims) }.get(dst, 0, rows * header.dims)
    }

    override fun close() = channel.close()

    companion object {
        const val VERSION = 1
        private const val HEADER = 128
        private val MAGIC = "GVEC".toByteArray()

        /** Null for a file that isn't a version-1 vector file or is truncated. */
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
                val fits = header.version == VERSION && header.dims > 0 && header.count >= 0 &&
                    keysAt == HEADER + header.count.toLong() * (header.dims + 12) && blobLengthAt + 4 <= buf.capacity() &&
                    blobLengthAt + 4 + (buf.getInt(blobLengthAt.toInt()).toLong() and 0xFFFFFFFFL) == buf.capacity().toLong()
                if (fits) VectorFile(file, channel, buf, header) else null.also { channel.close() }
            } catch (_: IOException) {
                channel.close()
                null
            }
        }

        private fun hex(buf: ByteBuffer, at: Int) = buildString(64) { for (i in 0 until 32) append("%02x".format(buf.get(at + i))) }
    }
}
