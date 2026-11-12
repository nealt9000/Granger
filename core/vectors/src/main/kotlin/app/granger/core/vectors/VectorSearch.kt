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

/**
 * Int8 search over vector files, as `tools/vectors/gvec.py`'s `int8_scores` computes it: brute force ([top]), or, with
 * [probes] (Plan SQ Task 2.2), only the lists of version 2 files whose centroids are nearest the query, across every
 * file at once, plus every version 1 file whole. Offline (`tools/vectors/ivf_spike.py`) 1,024 lists of 250 rows kept
 * 96% of brute force's top 24 reading 6% of the eval library's rows.
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
     * The [k] best rows over [files] (all of one dims), best first. Each file is split into ranges run on [pool]. With
     * [probes], a version 2 file contributes only its lists among the [probes] nearest centroids over all files.
     */
    fun top(query: FloatArray, files: List<VectorFile>, k: Int, pool: ExecutorService, probes: Int? = null): List<VectorHit> {
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
        val tasks = ranges.groupBy { it.first }.flatMap { (f, rs) ->
            rs.chunked(LISTS_PER_TASK).map { batch -> Callable { batch.flatMap { (_, from, until) -> scan(f, from, until, q, qScale, k) } } }
        }
        val best = PriorityQueue<VectorHit>(k + 1, compareBy { it.score })
        for (part in pool.invokeAll(tasks)) for (hit in part.get()) offer(best, hit, k)
        return best.sortedByDescending { it.score }
    }

    /** The row ranges of the [probes] lists, over every version 2 file, whose centroids score best against the query. */
    private fun nearestLists(files: List<VectorFile>, q: ByteArray, qScale: Float, probes: Int): List<Triple<VectorFile, Int, Int>> {
        val dims = q.size
        val best = PriorityQueue<Pair<Float, Pair<VectorFile, Int>>>(probes + 1, compareBy { it.first })
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
                if (best.size < probes || score > best.peek().first) {
                    best.add(score to (f to list))
                    if (best.size > probes) best.poll()
                }
            }
        }
        return best.map { (_, fl) -> Triple(fl.first, fl.first.listStart(fl.second), fl.first.listStart(fl.second + 1)) }
            .filter { it.second < it.third }
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
                // An empty chunk's all-zero vector was written with a NaN scale (wikipedia_en_mathematics, 31 rows): a NaN
                // compares as the largest score and would hold a slot. Plan SQ found it; such rows are skipped.
                if (score.isNaN() || score.isInfinite()) continue
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
