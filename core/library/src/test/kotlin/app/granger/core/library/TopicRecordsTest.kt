package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan O6 Task 3: the records reader against the fixture tools/topicrecords builds (300 articles in three blocks; Q10 a
 * broad topic holding Q20, Q21 and Q22; Q30 holding Q20; Q20 with a subject, a room, coordinates, a link and a flag).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TopicRecordsTest {
    private fun bytes(ext: String) = javaClass.classLoader!!.getResourceAsStream("records/wikipedia_xx_test_2026-11.$ext")!!.readBytes()
    private val grec = bytes("grec")
    private val directory = RecordsDirectory.parse(bytes("grdir"))!!

    private fun get(qid: Long, block: ByteArray? = null): TopicRecord? {
        val entry = directory.blockFor(qid) ?: return null
        val bytes = block ?: grec.copyOfRange(entry.offset.toInt(), entry.offset.toInt() + entry.length)
        return TopicRecords.find(bytes, entry, qid, "wikipedia_xx_online")
    }

    @Test fun theDirectoryKnowsItsBlocksAndSource() {
        assertThat(directory.records).isEqualTo(300)
        assertThat(directory.blocks).isEqualTo(3)
        assertThat(directory.sourceSha256).hasLength(64)
        assertThat(directory.blockFor(128)!!.index).isEqualTo(0)
        assertThat(directory.blockFor(129)!!.index).isEqualTo(1)
        assertThat(directory.blockFor(300)!!.index).isEqualTo(2)
        assertThat(directory.blockFor(0)).isNull()
    }

    @Test fun aPlainArticleAtEachBlockEdge() {
        for (q in listOf(1L, 128L, 129L, 256L, 257L, 300L)) {
            val r = get(q)!!
            assertThat(r.path).isEqualTo("A$q")
            assertThat(r.rank).isEqualTo(q.toInt())
            assertThat(r.parts).isNull()
        }
        assertThat(get(301)).isNull()
    }

    @Test fun anArticleCarriesItsSubjectsRoomsPlaceLinksFlagAndWhatLeadsHere() {
        val r = get(20)!!
        assertThat(r.subjects).containsExactly("history")
        assertThat(r.rooms).containsExactly("history.wars")
        assertThat(r.coordinates).isEqualTo(51.5 to -0.12)
        assertThat(r.links).containsExactly("P50" to "Q5")
        assertThat(r.flags).isEqualTo(1)
        assertThat(r.other).isEqualTo("Otro")
        assertThat(r.holders.map { it.qid }).containsExactly(30L, 10L).inOrder()
        assertThat(r.holderCount).isEqualTo(2)
    }

    @Test fun aBroadTopicsPartsReadAsTheLocalIndexReadsThem() {
        val parts = get(10)!!.parts!!
        assertThat(parts.under).isEqualTo(40)
        assertThat(parts.inlinks).isEqualTo(900)
        assertThat(parts.groups.single().name).isEqualTo("Kinds")
        assertThat(parts.kinds.map { it.qid }).containsExactly(20L)
        assertThat(parts.links.map { it.qid }).containsExactly(21L)
        assertThat(parts.people).containsExactly(22L)
        assertThat(get(10)!!.items[20]).isEqualTo(RecordRef(20, "A20", 20, 1))
    }

    @Test fun aCorruptBlockIsRefused() {
        val entry = directory.blockFor(130)!!
        val bad = grec.copyOfRange(entry.offset.toInt(), entry.offset.toInt() + entry.length).also { it[40] = (it[40].toInt() xor 0xFF).toByte() }
        assertThrows(CorruptBlock::class.java) { get(130, bad) }
        assertThrows(CorruptBlock::class.java) { get(130, bad.copyOf(bad.size - 1)) }
    }

    @Test fun aV1DirectoryIsNotRead() {
        assertThat(RecordsDirectory.parse("GRD1\u0000\u0000\u0000\u0000".toByteArray())).isNull()
    }
}
