package app.granger.core.vectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JVM's VectorFileTest and VectorFileWriteTest, on the iOS Simulator (mmap and fopen paths). Plan I1, from the I0 trial. */
class VectorFileIosTest {
    // tiny.gvec, written by tools/vectors/tests/test_gvec.py.
    private val tiny = "47564543010001000400000003000000ababababababababababababababababababababababababababababababababcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdb000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000007f0000005f7f0000000081400402013ca069ce3bbbc6e63b369fae5d28de7efc0000000000000001ffffffffffffffff000000000c000000180000002e000000000000000600412f4b6e6f74010000000600412f4b6e6f740700000010004f454250532f633030312e7868746d6c".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun tmp(name: String) = NSTemporaryDirectory() + NSUUID().UUIDString + "-" + name
    private fun fixture() = tmp("tiny.gvec").also { writeFileAtomically(it, tiny) }

    @Test fun readsThePythonFixture() {
        val f = VectorFile.open(fixture())!!
        assertEquals(VectorFile.Header(1, 1, 4, 3, "ab".repeat(32), "cd".repeat(32)), f.header)
        assertEquals(listOf(ChunkKey("A/Knot", 0), ChunkKey("A/Knot", 1), ChunkKey("OEBPS/c001.xhtml", 7)), (0 until 3).map { f.key(it) })
        assertEquals("369fae5d28de7efc", f.hash(0))
        assertEquals("ffffffffffffffff", f.hash(2))
        assertTrue(abs(f.scale(0) - 1f / 127f) < 1e-6f)
        f.close()
    }

    @Test fun searchRanksTheFixtureByCosine() = runBlocking {
        val f = VectorFile.open(fixture())!!
        val hits = VectorSearch.top(floatArrayOf(1f, 0f, 0f, 0f), listOf(f), 3, Dispatchers.IO)
        assertEquals(listOf(0, 1, 2), hits.map { it.row })
        assertTrue(abs(hits[0].score - 1.0) < 0.02)
        assertTrue(abs(hits[1].score - 0.6) < 0.02)
    }

    @Test fun findsAKeysRowsAndCopiesThem() {
        val f = VectorFile.open(fixture())!!
        assertContentEquals(intArrayOf(0, 1), f.rowsOf("A/Knot"))
        assertContentEquals(intArrayOf(2), f.rowsOf("OEBPS/c001.xhtml"))
        assertContentEquals(intArrayOf(), f.rowsOf("A/Kno"))
        val row = ByteArray(4)
        f.copyRow(0, row)
        assertContentEquals(byteArrayOf(127, 0, 0, 0), row)
        f.close()
    }

    @Test fun rejectsOtherFilesAndTruncation() {
        assertNull(VectorFile.open(tmp("x.gvec").also { writeFileAtomically(it, "not a vector file".encodeToByteArray()) }))
        assertNull(VectorFile.open(tmp("short.gvec").also { writeFileAtomically(it, tiny.copyOf(tiny.size - 10)) }))
        assertNull(VectorFile.open(tmp("missing.gvec")))
    }

    @Test fun writesWhatThePythonFixtureHolds() {
        val out = tmp("w.gvec")
        val vectors = listOf(floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(0.6f, 0.8f, 0f, 0f), floatArrayOf(0f, 0f, -1f, 0.5f))
        VectorFile.write(
            out, vectors, 4, listOf("369fae5d28de7efc", "0000000000000001", "ffffffffffffffff"),
            listOf(ChunkKey("A/Knot", 0), ChunkKey("A/Knot", 1), ChunkKey("OEBPS/c001.xhtml", 7)), 1, "ab".repeat(32), "cd".repeat(32),
        )
        val back = VectorFile.open(out)!!
        val bytes = ByteArray(tiny.size)
        mapReadOnly(out)!!.also { it.copy(0, bytes, 0, bytes.size); assertEquals(tiny.size.toLong(), it.size) }.close()
        assertContentEquals(tiny, bytes)
        back.close()
    }

    @Test fun topKeepsTheBestAcrossRanges() = runBlocking {
        // 70,000 rows: two ranges of 65,536, so the merge across tasks runs.
        val n = 70_000
        val vectors = List(n) { i -> floatArrayOf(1f, (i % 1000) / 1000f, 0f, 0f) }
        val path = tmp("big.gvec")
        VectorFile.write(path, vectors, 4, List(n) { "0".repeat(16) }, List(n) { ChunkKey("k", it) }, 1, "0".repeat(64), "0".repeat(64))
        val hits = VectorSearch.top(floatArrayOf(1f, 0f, 0f, 0f), listOf(VectorFile.open(path)!!), 5, Dispatchers.IO)
        assertEquals(5, hits.size)
        assertTrue(hits.all { it.row % 1000 == 0 })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }
}
