package app.granger.core.vectors

/** Where a vector came from: an article path or a chapter href, and the chunk's ordinal there. */
data class ChunkKey(val key: String, val ordinal: Int)

/**
 * A pack's or book's precomputed vectors (`tools/vectors/gvec.py` writes them; the layout is documented there), mapped
 * read-only. Vectors are int8 with one float scale each; each row also carries its chunk text's hash
 * ([app.granger.core.text.Chunkers.hash]) and its [ChunkKey].
 *
 * I0 trial: the common version of `core/vectors`' VectorFile. `java.nio` gives way to [MappedFile] (JVM: FileChannel;
 * iOS: mmap) and [LeBytes]; `File` gives way to a path, with `File` overloads in jvmMain.
 */
class VectorFile private constructor(val path: String, private val map: MappedFile, val header: Header) : AutoCloseable {

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
    private val keysAt = map.long(80)
    private val blobAt = keysAt + 4L * header.count + 4

    fun scale(row: Int): Float = map.float(scalesAt + 4L * row)

    fun hash(row: Int): String = hex(map, hashesAt + 8L * row, 8)

    fun key(row: Int): ChunkKey {
        val at = blobAt + (map.int(keysAt + 4L * row).toLong() and 0xFFFFFFFFL)
        val ordinal = map.int(at)
        val length = map.short(at + 4)
        val bytes = ByteArray(length)
        map.copy(at + 6, bytes, 0, length)
        return ChunkKey(bytes.decodeToString(), ordinal)
    }

    /** Copies [rows] vectors starting at [from] into [dst] (rows × dims bytes). */
    internal fun copyRows(from: Int, rows: Int, dst: ByteArray) {
        map.copy(HEADER + from.toLong() * header.dims, dst, 0, rows * header.dims)
    }

    override fun close() = map.close()

    companion object {
        const val VERSION = 1
        private const val HEADER = 128
        private val MAGIC = "GVEC".encodeToByteArray()

        /** Null for a file that isn't a version-1 vector file or is truncated. */
        fun open(path: String): VectorFile? {
            val map = mapReadOnly(path) ?: return null
            if (map.size < HEADER || (0 until 4).any { map.byte(it.toLong()) != MAGIC[it] }) return null.also { map.close() }
            val header = Header(
                version = map.short(4),
                chunkerVersion = map.short(6),
                dims = map.int(8),
                count = map.int(12),
                embedderSha256 = hex(map, 16, 32),
                sourceSha256 = hex(map, 48, 32),
            )
            val keysAt = map.long(80)
            val blobLengthAt = keysAt + 4L * header.count
            val fits = header.version == VERSION && header.dims > 0 && header.count >= 0 &&
                keysAt == HEADER + header.count.toLong() * (header.dims + 12) && blobLengthAt + 4 <= map.size &&
                blobLengthAt + 4 + (map.int(blobLengthAt).toLong() and 0xFFFFFFFFL) == map.size
            return if (fits) VectorFile(path, map, header) else null.also { map.close() }
        }

        /**
         * Writes [vectors] (each truncated to [dims], renormalized and quantized, as `gvec.py` does) with their chunk
         * [hashes] (16 hex digits) and [keys]. Used on the phone for books a user adds, and by tests.
         */
        fun write(
            path: String,
            vectors: List<FloatArray>,
            dims: Int,
            hashes: List<String>,
            keys: List<ChunkKey>,
            chunkerVersion: Int,
            embedderSha256: String,
            sourceSha256: String,
        ) {
            require(vectors.size == hashes.size && vectors.size == keys.size) { "vectors, hashes and keys differ in count" }
            val blob = LeBytes()
            val offsets = keys.map { k ->
                val at = blob.size
                val bytes = k.key.encodeToByteArray()
                blob.int(k.ordinal).short(bytes.size).put(bytes)
                at
            }
            val count = vectors.size
            val keysAt = HEADER.toLong() + count.toLong() * (dims + 12)
            val out = LeBytes(HEADER + count * (dims + 16) + blob.size + 4)
            out.put(MAGIC).short(VERSION).short(chunkerVersion).int(dims).int(count)
            out.put(unhex(embedderSha256, 32)).put(unhex(sourceSha256, 32)).long(keysAt)
            out.put(ByteArray(HEADER - out.size))
            val quantized = vectors.map { VectorSearch.quantize(it, dims) }
            quantized.forEach { out.put(it.first) }
            quantized.forEach { out.float(it.second) }
            hashes.forEach { out.put(unhex(it, 8)) }
            offsets.forEach { out.int(it) }
            out.int(blob.size)
            out.put(blob.bytes())
            writeFileAtomically(path, out.bytes())
        }

        private fun unhex(hex: String, bytes: Int): ByteArray {
            require(hex.length == 2 * bytes) { "expected ${2 * bytes} hex digits" }
            return ByteArray(bytes) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }

        private const val DIGITS = "0123456789abcdef"

        private fun hex(map: MappedFile, at: Long, bytes: Int) = buildString(2 * bytes) {
            for (i in 0 until bytes) {
                val b = map.byte(at + i).toInt() and 0xFF
                append(DIGITS[b shr 4]).append(DIGITS[b and 0xF])
            }
        }
    }
}
