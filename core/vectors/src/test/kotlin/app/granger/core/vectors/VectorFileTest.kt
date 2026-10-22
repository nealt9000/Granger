package app.granger.core.vectors

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture() = File(javaClass.classLoader.getResource("tiny.gvec")!!.toURI())

    // tiny.gvec is written by tools/vectors/tests/test_gvec.py, so the Python writer and this reader agree.
    @Test fun readsThePythonFixture() {
        val f = VectorFile.open(fixture())!!
        assertThat(f.header).isEqualTo(VectorFile.Header(1, 1, 4, 3, "ab".repeat(32), "cd".repeat(32)))
        assertThat((0 until 3).map { f.key(it) })
            .containsExactly(ChunkKey("A/Knot", 0), ChunkKey("A/Knot", 1), ChunkKey("OEBPS/c001.xhtml", 7)).inOrder()
        assertThat(f.hash(0)).isEqualTo("369fae5d28de7efc")
        assertThat(f.hash(2)).isEqualTo("ffffffffffffffff")
        assertThat(f.scale(0)).isWithin(1e-6f).of(1f / 127f)
    }

    @Test fun searchRanksTheFixtureByCosine() {
        val f = VectorFile.open(fixture())!!
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val hits = VectorSearch.top(floatArrayOf(1f, 0f, 0f, 0f), listOf(f), 3, pool)
        pool.shutdown()
        assertThat(hits.map { it.row }).containsExactly(0, 1, 2).inOrder()
        assertThat(hits[0].score.toDouble()).isWithin(0.02).of(1.0)
        assertThat(hits[1].score.toDouble()).isWithin(0.02).of(0.6)
    }

    @Test fun rejectsOtherFilesAndTruncation() {
        assertThat(VectorFile.open(tmp.newFile("x.gvec").apply { writeText("not a vector file") })).isNull()
        val bytes = fixture().readBytes()
        assertThat(VectorFile.open(tmp.newFile("short.gvec").apply { writeBytes(bytes.copyOf(bytes.size - 10)) })).isNull()
        assertThat(VectorFile.open(File(tmp.root, "missing.gvec"))).isNull()
    }
}
