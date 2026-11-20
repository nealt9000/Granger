package app.granger.core.library

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.getenv
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The records fixture read on iOS: zlib inflate and CRC32 through the platform's zlib (Plan I3 Task 3). */
class TopicRecordsIosTest {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun bytes(ext: String): ByteArray {
        val repo = getenv("GRANGER_REPO")?.toKString() ?: error("GRANGER_REPO not set")
        val data = NSData.dataWithContentsOfFile("$repo/core/records/src/jvmTest/resources/records/wikipedia_xx_test_2026-11.$ext")!!
        val out = ByteArray(data.length.toInt())
        out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    @Test fun recordsReadAsOnTheJvm() {
        val grec = bytes("grec")
        val dir = RecordsDirectory.parse(bytes("grdir"))!!
        assertEquals(300, dir.records)
        fun get(q: Long): TopicRecord? {
            val e = dir.blockFor(q) ?: return null
            return TopicRecords.find(grec.copyOfRange(e.offset.toInt(), e.offset.toInt() + e.length), e, q, "wikipedia_xx_online")
        }
        for (q in listOf(1L, 128L, 129L, 300L)) assertEquals("A$q", get(q)!!.path)
        val r = get(20)!!
        assertEquals(listOf("history"), r.subjects)
        assertEquals(51.5 to -0.12, r.coordinates)
        assertEquals(listOf(30L, 10L), r.holders.map { it.qid })
        val parts = get(10)!!.parts!!
        assertEquals(40, parts.under)
        assertEquals(listOf(20L), parts.kinds.map { it.qid })
        assertEquals(listOf(21L), parts.links.map { it.qid })
        assertEquals(listOf(22L), parts.people)
        assertNull(get(301))
        val e = dir.blockFor(1)!!
        val bad = grec.copyOfRange(e.offset.toInt(), e.offset.toInt() + e.length).also { it[10] = (it[10] + 1).toByte() }
        assertFailsWith<CorruptBlock> { TopicRecords.find(bad, e, 1, "x") }
    }
}
