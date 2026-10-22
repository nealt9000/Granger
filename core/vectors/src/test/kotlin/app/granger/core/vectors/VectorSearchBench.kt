package app.granger.core.vectors

import java.util.concurrent.Executors
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Scan speed on this machine; runs only with `-Pbench` (`./gradlew :core:vectors:test -Pbench --tests '*Bench'`). */
class VectorSearchBench {
    @Test fun scan() {
        assumeTrue(System.getProperty("bench") != null)
        val rng = Random(1)
        val rows = 1_000_000
        val file = kotlin.io.path.createTempFile(suffix = ".gvec").toFile().apply { deleteOnExit() }
        TestGvec.write(file, List(rows) { FloatArray(256) { rng.nextFloat() - 0.5f } })
        val f = VectorFile.open(file)!!
        for (threads in listOf(1, 4, 8)) {
            val pool = Executors.newFixedThreadPool(threads)
            val q = FloatArray(256) { rng.nextFloat() - 0.5f }
            VectorSearch.top(q, listOf(f), 24, pool)
            val start = System.nanoTime()
            repeat(5) { VectorSearch.top(q, listOf(f), 24, pool) }
            println("bench: $threads threads, ${(System.nanoTime() - start) / 5 / 1_000_000} ms per 1M rows")
            pool.shutdown()
        }
    }
}
