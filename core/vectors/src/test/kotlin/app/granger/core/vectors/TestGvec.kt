package app.granger.core.vectors

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Writes a test vector file with blank hashes and shas. */
object TestGvec {
    fun write(file: File, vectors: List<FloatArray>, keys: List<ChunkKey> = vectors.indices.map { ChunkKey("A/$it", 0) }): File {
        val dims = vectors.firstOrNull()?.size ?: 4
        VectorFile.write(file, vectors, dims, vectors.map { "0".repeat(16) }, keys, 1, "0".repeat(64), "0".repeat(64))
        return file
    }

    /**
     * A version 2 file (as `tools/vectors/ivf.py` writes them): [lists]' rows in list order, each list's centroid its rows'
     * normalized mean, quantized as rows are.
     */
    fun writeLists(file: File, lists: List<List<FloatArray>>): File {
        val rows = lists.flatten()
        write(file, rows)
        val dims = rows.first().size
        val listsAt = file.length()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(2).array())
            raf.seek(88)
            raf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(listsAt).array())
            raf.seek(listsAt)
            val out = ByteBuffer.allocate(4 + lists.size * (dims + 4) + 4 * (lists.size + 1)).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(lists.size)
            val scales = lists.map { list ->
                val mean = FloatArray(dims) { d -> list.sumOf { it[d].toDouble() }.toFloat() }
                val n = max(sqrt(mean.sumOf { (it * it).toDouble() }).toFloat(), 1e-12f)
                val scale = max(mean.maxOf { abs(it / n) }, 1e-12f) / 127f
                for (d in 0 until dims) out.put((mean[d] / n / scale).roundToInt().coerceIn(-127, 127).toByte())
                scale
            }
            scales.forEach { out.putFloat(it) }
            var start = 0
            out.putInt(0)
            lists.forEach { start += it.size; out.putInt(start) }
            raf.write(out.array())
        }
        return file
    }
}
