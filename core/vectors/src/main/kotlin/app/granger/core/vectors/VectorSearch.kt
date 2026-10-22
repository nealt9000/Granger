package app.granger.core.vectors

import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One search result: a row of [file], scored as the int8 dot product times both scales (≈ cosine). */
data class VectorHit(val file: VectorFile, val row: Int, val score: Float)

/** Brute-force int8 search over vector files, as `tools/vectors/gvec.py`'s `int8_scores` computes it. */
object VectorSearch {
    private const val BLOCK = 4096
    private const val RANGE = 65536 // rows per task

    /** The query truncated to [dims], renormalized and quantized: (int8 values, scale). */
    fun quantize(query: FloatArray, dims: Int): Pair<ByteArray, Float> {
        require(query.size >= dims) { "query has ${query.size} dims, files have $dims" }
        var norm = 0.0
        for (i in 0 until dims) norm += query[i] * query[i]
        val n = max(sqrt(norm), 1e-12).toFloat()
        var maxAbs = 0f
        for (i in 0 until dims) maxAbs = max(maxAbs, abs(query[i] / n))
        val scale = max(maxAbs, 1e-12f) / 127f
        return ByteArray(dims) { (query[it] / n / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
    }

    /** The [k] best rows over [files] (all of one dims), best first. Each file is split into ranges run on [pool]. */
    fun top(query: FloatArray, files: List<VectorFile>, k: Int, pool: ExecutorService): List<VectorHit> {
        if (files.isEmpty() || k <= 0) return emptyList()
        val dims = files.first().header.dims
        require(files.all { it.header.dims == dims }) { "vector files differ in dims" }
        val (q, qScale) = quantize(query, dims)
        val tasks = files.flatMap { f -> (0 until f.header.count step RANGE).map { from -> Callable { scan(f, from, minOf(f.header.count, from + RANGE), q, qScale, k) } } }
        val best = PriorityQueue<VectorHit>(k + 1, compareBy { it.score })
        for (part in pool.invokeAll(tasks)) for (hit in part.get()) offer(best, hit, k)
        return best.sortedByDescending { it.score }
    }

    private fun scan(file: VectorFile, from: Int, until: Int, q: ByteArray, qScale: Float, k: Int): List<VectorHit> {
        val dims = q.size
        val block = ByteArray(BLOCK * dims)
        val best = PriorityQueue<VectorHit>(k + 1, compareBy { it.score })
        var row = from
        while (row < until) {
            val n = minOf(BLOCK, until - row)
            file.copyRows(row, n, block)
            for (r in 0 until n) {
                var dot = 0
                val base = r * dims
                for (i in 0 until dims) dot += block[base + i] * q[i]
                val score = dot * file.scale(row + r) * qScale
                if (best.size < k || score > best.peek().score) offer(best, VectorHit(file, row + r, score), k)
            }
            row += n
        }
        return best.toList()
    }

    private fun offer(best: PriorityQueue<VectorHit>, hit: VectorHit, k: Int) {
        best.add(hit)
        if (best.size > k) best.poll()
    }
}
