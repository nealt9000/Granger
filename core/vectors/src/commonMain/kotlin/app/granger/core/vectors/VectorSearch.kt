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
 * Int8 search over vector files, as `tools/vectors/gvec.py`'s `int8_scores` computes it: brute force ([top]), or, with
 * [probes] (Plan SQ Task 2.2), only the lists of version 2 files whose centroids are nearest the query, across every
 * file at once, plus every version 1 file whole. Offline (`tools/vectors/ivf_spike.py`) 1,024 lists of 250 rows kept
 * 96% of brute force's top 24 reading 6% of the eval library's rows.
 *
 * Common to Android and iOS (Plan I1): tasks are coroutines on a [CoroutineDispatcher]; the JVM keeps the blocking
 * `ExecutorService` form (`VectorFiles.kt`).
 */
object VectorSearch {
    private const val BLOCK = 4096
    private const val RANGE = 65536 // rows per task
    private const val LISTS_PER_TASK = 64

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

    /**
     * The [k] best rows over [files] (all of one dims), best first. Each file is split into ranges run on [dispatcher].
     * With [probes], a version 2 file contributes only its lists among the [probes] nearest centroids over all files.
     */
    suspend fun top(query: FloatArray, files: List<VectorFile>, k: Int, dispatcher: CoroutineDispatcher, probes: Int? = null): List<VectorHit> {
        if (files.isEmpty() || k <= 0) return emptyList()
        val dims = files.first().header.dims
        require(files.all { it.header.dims == dims }) { "vector files differ in dims" }
        val (q, qScale) = quantize(query, dims)
        val ranges = if (probes == null || files.none { it.listCount > 0 }) {
            files.flatMap { f -> (0 until f.header.count step RANGE).map { from -> Triple(f, from, minOf(f.header.count, from + RANGE)) } }
        } else {
            nearestLists(files, q, qScale, probes) + files.filter { it.listCount == 0 }
                .flatMap { f -> (0 until f.header.count step RANGE).map { from -> Triple(f, from, minOf(f.header.count, from + RANGE)) } }
        }
        // A task per range of a file; the lists, being short, are batched a file's worth at a time.
        val parts = coroutineScope {
            ranges.groupBy { it.first }.flatMap { (f, rs) ->
                rs.chunked(LISTS_PER_TASK).map { batch -> async(dispatcher) { batch.flatMap { (_, from, until) -> scan(f, from, until, q, qScale, k) } } }
            }.awaitAll()
        }
        val best = TopK<VectorHit>(k) { it.score }
        for (part in parts) for (hit in part) best.offer(hit)
        return best.sortedBest()
    }

    /** The row ranges of the [probes] lists, over every version 2 file, whose centroids score best against the query. */
    private fun nearestLists(files: List<VectorFile>, q: ByteArray, qScale: Float, probes: Int): List<Triple<VectorFile, Int, Int>> {
        val dims = q.size
        val best = TopK<Pair<Float, Pair<VectorFile, Int>>>(probes) { it.first }
        for (f in files) {
            val n = f.listCount
            if (n == 0) continue
            val cents = ByteArray(n * dims)
            f.copyCentroids(cents)
            for (list in 0 until n) {
                var dot = 0
                val base = list * dims
                for (i in 0 until dims) dot += cents[base + i] * q[i]
                val score = dot * f.centroidScale(list) * qScale
                if (score.isNaN()) continue
                if (best.wants(score)) best.offer(score to (f to list))
            }
        }
        return best.items().map { (_, fl) -> Triple(fl.first, fl.first.listStart(fl.second), fl.first.listStart(fl.second + 1)) }
            .filter { it.second < it.third }
    }

    private fun scan(file: VectorFile, from: Int, until: Int, q: ByteArray, qScale: Float, k: Int): List<VectorHit> {
        val dims = q.size
        val block = ByteArray(BLOCK * dims)
        val best = TopK<VectorHit>(k) { it.score }
        var row = from
        while (row < until) {
            val n = minOf(BLOCK, until - row)
            file.copyRows(row, n, block)
            for (r in 0 until n) {
                var dot = 0
                val base = r * dims
                for (i in 0 until dims) dot += block[base + i] * q[i]
                val score = dot * file.scale(row + r) * qScale
                // An empty chunk's all-zero vector was written with a NaN scale (wikipedia_en_mathematics, 31 rows): a NaN
                // compares as the largest score and would hold a slot. Plan SQ found it; such rows are skipped.
                if (score.isNaN() || score.isInfinite()) continue
                if (best.wants(score)) best.offer(VectorHit(file, row + r, score))
            }
            row += n
        }
        return best.items()
    }
}

/** Keeps the [k] highest-scoring items: a binary min-heap on [score] (common code has no `PriorityQueue`). */
internal class TopK<T>(private val k: Int, private val score: (T) -> Float) {
    private val heap = ArrayList<T>(k + 1)

    fun wants(s: Float) = heap.size < k || s > score(heap[0])

    fun offer(item: T) {
        if (k <= 0) return
        if (heap.size < k) {
            heap.add(item); up(heap.size - 1)
        } else if (score(item) > score(heap[0])) {
            heap[0] = item; down(0)
        }
    }

    fun items(): List<T> = heap.toList()
    fun sortedBest(): List<T> = heap.sortedByDescending(score)

    private fun up(i0: Int) {
        var i = i0
        while (i > 0) {
            val p = (i - 1) / 2
            if (score(heap[p]) <= score(heap[i])) return
            swap(i, p); i = p
        }
    }

    private fun down(i0: Int) {
        var i = i0
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < heap.size && score(heap[l]) < score(heap[m])) m = l
            if (r < heap.size && score(heap[r]) < score(heap[m])) m = r
            if (m == i) return
            swap(i, m); i = m
        }
    }

    private fun swap(a: Int, b: Int) { val t = heap[a]; heap[a] = heap[b]; heap[b] = t }
}
