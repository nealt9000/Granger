package app.granger.core.vectors

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pool = Executors.newFixedThreadPool(4)

    @After fun stop() = pool.shutdown()

    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun random(rng: Random, n: Int, dims: Int) = List(n) { unit(FloatArray(dims) { rng.nextFloat() * 2 - 1 }) }

    @Test fun topKMatchesAFloatReferenceAcrossFilesAndRanges() {
        val rng = Random(7)
        val a = random(rng, 70_000, 64) // more than one range
        val b = random(rng, 1_000, 64)
        val files = listOf(VectorFile.open(TestGvec.write(tmp.newFile(), a))!!, VectorFile.open(TestGvec.write(tmp.newFile(), b))!!)
        val q = unit(FloatArray(64) { rng.nextFloat() * 2 - 1 })
        val hits = VectorSearch.top(q, files, 24, pool)
        val reference = (a.map { 0 to it } + b.map { 1 to it }).mapIndexed { i, (f, v) -> Triple(f, i, v.indices.sumOf { (v[it] * q[it]).toDouble() }) }
            .sortedByDescending { it.third }.take(24)
        assertThat(hits).hasSize(24)
        assertThat(hits.map { it.score }).isInOrder(Comparator.reverseOrder<Float>())
        // int8 keeps the ranking up to near-ties: most of the reference top 24 is found, and the best is the same.
        val found = hits.map { (if (it.file === files[0]) 0 else 1) to it.row }.toSet()
        val ref = reference.map { (f, i, _) -> f to (if (f == 0) i else i - a.size) }
        assertThat(found.intersect(ref.toSet()).size).isAtLeast(20)
        assertThat(found).contains(ref.first())
    }

    @Test fun queryIsTruncatedToTheFilesDims() {
        val f = VectorFile.open(TestGvec.write(tmp.newFile(), listOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f))))!!
        val hits = VectorSearch.top(floatArrayOf(1f, 0f, 9f, 9f), listOf(f), 1, pool)
        assertThat(hits.single().row).isEqualTo(1)
    }

    @Test fun emptyInputsGiveNothing() {
        val empty = VectorFile.open(TestGvec.write(tmp.newFile(), emptyList(), emptyList()))!!
        assertThat(VectorSearch.top(floatArrayOf(1f, 0f, 0f, 0f), listOf(empty), 5, pool)).isEmpty()
        assertThat(VectorSearch.top(floatArrayOf(1f), emptyList(), 5, pool)).isEmpty()
    }
}
