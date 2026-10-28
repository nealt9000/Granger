package app.granger.core.vectors

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One search result: a row of [file], scored as the int8 dot product times both scales (≈ cosine). */
data class VectorHit(val file: VectorFile, val row: Int, val score: Float)

/**
 * Brute-force int8 search over vector files, as `tools/vectors/gvec.py`'s `int8_scores` computes it.
 *
 * I0 trial: ranges run as coroutines on a [CoroutineDispatcher] instead of Callables on an ExecutorService (jvmMain
 * keeps the ExecutorService overload), and a small [TopK] heap stands in for `java.util.PriorityQueue`.
 */
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

    /** The [k] best rows over [files] (all of one dims), best first. Each file is split into ranges run on [dispatcher]. */
    suspend fun top(query: FloatArray, files: List<VectorFile>, k: Int, dispatcher: CoroutineDispatcher): List<VectorHit> {
        if (files.isEmpty() || k <= 0) return emptyList()
        val dims = files.first().header.dims
        require(files.all { it.header.dims == dims }) { "vector files differ in dims" }
        val (q, qScale) = quantize(query, dims)
        val parts = coroutineScope {
            files.flatMap { f ->
                (0 until f.header.count step RANGE).map { from ->
                    async(dispatcher) { scan(f, from, minOf(f.header.count, from + RANGE), q, qScale, k) }
                }
            }.awaitAll()
        }
        val best = TopK(k)
        for (part in parts) for (hit in part) best.offer(hit)
        return best.sortedBest()
    }

    private fun scan(file: VectorFile, from: Int, until: Int, q: ByteArray, qScale: Float, k: Int): List<VectorHit> {
        val dims = q.size
        val block = ByteArray(BLOCK * dims)
        val best = TopK(k)
        var row = from
        while (row < until) {
            val n = minOf(BLOCK, until - row)
            file.copyRows(row, n, block)
            for (r in 0 until n) {
                var dot = 0
                val base = r * dims
                for (i in 0 until dims) dot += block[base + i] * q[i]
                val score = dot * file.scale(row + r) * qScale
                if (best.wants(score)) best.offer(VectorHit(file, row + r, score))
            }
            row += n
        }
        return best.items()
    }
}

/** Keeps the [k] highest-scoring hits: a binary min-heap on score. */
internal class TopK(private val k: Int) {
    private val heap = ArrayList<VectorHit>(k + 1)

    fun wants(score: Float) = heap.size < k || score > heap[0].score

    fun offer(hit: VectorHit) {
        if (heap.size < k) {
            heap.add(hit); up(heap.size - 1)
        } else if (hit.score > heap[0].score) {
            heap[0] = hit; down(0)
        }
    }

    fun items(): List<VectorHit> = heap.toList()
    fun sortedBest(): List<VectorHit> = heap.sortedByDescending { it.score }

    private fun up(i0: Int) {
        var i = i0
        while (i > 0) {
            val p = (i - 1) / 2
            if (heap[p].score <= heap[i].score) return
            swap(i, p); i = p
        }
    }

    private fun down(i0: Int) {
        var i = i0
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < heap.size && heap[l].score < heap[m].score) m = l
            if (r < heap.size && heap[r].score < heap[m].score) m = r
            if (m == i) return
            swap(i, m); i = m
        }
    }

    private fun swap(a: Int, b: Int) { val t = heap[a]; heap[a] = heap[b]; heap[b] = t }
}
