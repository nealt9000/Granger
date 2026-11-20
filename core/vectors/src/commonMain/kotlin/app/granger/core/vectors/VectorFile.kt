package app.granger.core.vectors

/** Where a vector came from: an article path or a chapter href, and the chunk's ordinal there. */
data class ChunkKey(val key: String, val ordinal: Int)

/**
 * A pack's or book's precomputed vectors (`tools/vectors/gvec.py` writes them; the layout is documented there), mapped
 * read-only. Vectors are int8 with one float scale each; each row also carries its chunk text's hash
 * ([app.granger.core.text.Chunkers.hash]) and its [ChunkKey]. A version 2 file (`tools/vectors/ivf.py`, Plan SQ) keeps its
 * rows grouped into lists around [listCount] centroids, so a search can read only the nearest lists ([VectorSearch]).
 *
 * Common to Android and iOS (Plan I1): reads go through [MappedFile]; the JVM keeps `File` overloads (`VectorFiles.kt`).
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

    fun hash(row: Int): String = hexOf(map, hashesAt + 8L * row, 8)

    fun key(row: Int): ChunkKey {
        val at = blobAt + (map.int(keysAt + 4L * row).toLong() and 0xFFFFFFFFL)
        val ordinal = map.int(at)
        val length = map.short(at + 4)
        val bytes = ByteArray(length)
        map.copy(at + 6, bytes, 0, length)
        return ChunkKey(bytes.decodeToString(), ordinal)
    }

    private val listsAt: Long = if (header.version >= 2) map.long(LISTS_AT) else -1L

    /** How many lists the rows are grouped in; 0 for a version 1 file, read whole. */
    val listCount: Int = if (listsAt < 0) 0 else map.int(listsAt)

    private val centroidsAt = listsAt + 4
    private val centroidScalesAt = centroidsAt + listCount.toLong() * header.dims
    private val startsAt = centroidScalesAt + 4L * listCount

    /** The first row of list [list]; `listStart(listCount)` is [Header.count]. */
    fun listStart(list: Int): Int = map.int(startsAt + 4L * list)

    fun centroidScale(list: Int): Float = map.float(centroidScalesAt + 4L * list)

    /** Copies every centroid (listCount × dims int8) into [dst]. */
    internal fun copyCentroids(dst: ByteArray) {
        map.copy(centroidsAt, dst, 0, listCount * header.dims)
    }

    /** Copies [rows] vectors starting at [from] into [dst] (rows × dims bytes). */
    internal fun copyRows(from: Int, rows: Int, dst: ByteArray) {
        map.copy(HEADER + from.toLong() * header.dims, dst, 0, rows * header.dims)
    }

    /** The rows whose key is [key] (an article path or chapter href), by one pass over the keys comparing bytes. */
    fun rowsOf(key: String): IntArray {
        val want = key.encodeToByteArray()
        val out = ArrayList<Int>()
        for (r in 0 until header.count) {
            val at = blobAt + (map.int(keysAt + 4L * r).toLong() and 0xFFFFFFFFL)
            if (map.short(at + 4) != want.size) continue
            var i = 0
            while (i < want.size && map.byte(at + 6 + i) == want[i]) i++
            if (i == want.size) out += r
        }
        return out.toIntArray()
    }

    /** Copies row [row]'s int8 vector into [dst] (dims bytes); times [scale] it approximates the stored vector. */
    fun copyRow(row: Int, dst: ByteArray) = copyRows(row, 1, dst)

    override fun close() = map.close()

    companion object {
        const val VERSION = 1
        /** The newest version read: 2 adds the lists ([listCount]). */
        const val MAX_VERSION = 2
        private const val HEADER = 128
        private const val LISTS_AT = 88L
        private val MAGIC = "GVEC".encodeToByteArray()

        /** Null for a file that isn't a version 1 or 2 vector file, or is truncated. */
        fun open(path: String): VectorFile? {
            val map = mapReadOnly(path) ?: return null
            return try {
                if (map.size < HEADER || (0 until 4).any { map.byte(it.toLong()) != MAGIC[it] }) return null.also { map.close() }
                val header = Header(
                    version = map.short(4),
                    chunkerVersion = map.short(6),
                    dims = map.int(8),
                    count = map.int(12),
                    embedderSha256 = hexOf(map, 16, 32),
                    sourceSha256 = hexOf(map, 48, 32),
                )
                val keysAt = map.long(80)
                val blobLengthAt = keysAt + 4L * header.count
                val size = map.size
                // Version 1 ends with the key blob; version 2 has the lists section after it (its offset at 88).
                val blobEnd = if (header.version >= 2) map.long(LISTS_AT) else size
                val fits = header.version in VERSION..MAX_VERSION && header.dims > 0 && header.count >= 0 &&
                    keysAt == HEADER + header.count.toLong() * (header.dims + 12) && blobLengthAt + 4 <= size &&
                    blobLengthAt + 4 + (map.int(blobLengthAt).toLong() and 0xFFFFFFFFL) == blobEnd &&
                    (header.version < 2 || listsFit(map, blobEnd, header.dims, header.count, size))
                if (fits) VectorFile(path, map, header) else null.also { map.close() }
            } catch (e: Exception) {
                // A truncated or garbled header can point a read past the end (IndexOutOfBounds on the JVM).
                map.close()
                null
            }
        }

        /** A version 2 file's lists section: n lists of centroids and scales, then n + 1 starts from 0 to count, ending the file. */
        private fun listsFit(map: MappedFile, at: Long, dims: Int, count: Int, size: Long): Boolean {
            if (at < 0 || at + 4 > size) return false
            val n = map.int(at)
            if (n <= 0) return false
            val startsAt = at + 4 + n.toLong() * (dims + 4)
            if (startsAt + 4L * (n + 1) != size) return false
            return map.int(startsAt) == 0 && map.int(startsAt + 4L * n) == count
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
    }
}
