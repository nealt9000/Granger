package app.granger.core.player

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class MusicQueueTest {
    private val ten = (0 until 10).map { "t$it" }
    private fun queue(ids: List<String> = ten, index: Int = 0) = MusicQueue(ids, index, Random(42))

    /** The ids in the order they will play. */
    private fun MusicQueue.played() = playOrder.map { ids[it] }

    @Test fun nextFollowsOriginalOrderAndStopsAtTheEnd() {
        val q = queue(listOf("a", "b", "c"), 1)
        assertThat(q.next()).isEqualTo(2)
        assertThat(q.index).isEqualTo(2)
        assertThat(q.next()).isNull()
        assertThat(q.index).isEqualTo(2)
    }

    @Test fun shuffleKeepsCurrentTrackFirst() {
        val q = queue(index = 4)
        q.setShuffle(true)
        assertThat(q.shuffle).isTrue()
        assertThat(q.index).isEqualTo(4)
        assertThat(q.playOrder.first()).isEqualTo(4)
        assertThat(q.playOrder).containsExactlyElementsIn(0 until 10)
        assertThat(q.playOrder).isNotEqualTo((0 until 10).toList())
        val visited = generateSequence { q.next() }.toList()
        assertThat(visited).containsExactlyElementsIn(q.playOrder.drop(1)).inOrder()
        assertThat(q.ids).isEqualTo(ten)   // the original order is untouched
    }

    @Test fun shuffleOffReturnsToOriginalOrderAtCurrentTrack() {
        val q = queue(index = 0)
        q.setShuffle(true)
        q.next(); q.next()
        val current = q.index
        q.setShuffle(false)
        assertThat(q.index).isEqualTo(current)
        assertThat(q.playOrder).isEqualTo((0 until 10).toList())
        assertThat(q.next()).isEqualTo(if (current == 9) null else current + 1)
    }

    @Test fun repeatOneReturnsSameIndex() {
        val q = queue(listOf("a", "b", "c"), 2)
        q.repeat = 2
        assertThat(q.next()).isEqualTo(2)
        assertThat(q.next()).isEqualTo(2)
    }

    @Test fun repeatAllWraps() {
        val q = queue(listOf("a", "b", "c"), 2)
        q.repeat = 1
        assertThat(q.next()).isEqualTo(0)
        assertThat(q.previous(0L)).isEqualTo(2)
        // Under shuffle, wrapping replays the same shuffled order.
        val s = queue(index = 3)
        s.repeat = 1
        s.setShuffle(true)
        val order = s.playOrder
        repeat(9) { s.next() }
        assertThat(s.next()).isEqualTo(3)
        assertThat(s.playOrder).isEqualTo(order)
    }

    @Test fun previousRestartsAfterThreeSeconds() {
        val q = queue(listOf("a", "b", "c"), 1)
        assertThat(q.previous(3_001L)).isEqualTo(1)
        assertThat(q.previous(3_000L)).isEqualTo(0)
        assertThat(q.index).isEqualTo(0)
    }

    @Test fun previousAtTheStartStaysWithRepeatOff() {
        val q = queue(listOf("a", "b", "c"), 0)
        assertThat(q.previous(0L)).isEqualTo(0)
        assertThat(q.index).isEqualTo(0)
    }

    @Test fun moveKeepsCurrentTrackCurrent() {
        val q = queue(listOf("a", "b", "c", "d"), 1)
        q.move(1, 3)
        assertThat(q.ids).containsExactly("a", "c", "d", "b").inOrder()
        assertThat(q.ids[q.index]).isEqualTo("b")
        q.move(3, 0)
        assertThat(q.ids).containsExactly("b", "a", "c", "d").inOrder()
        assertThat(q.index).isEqualTo(0)
        q.move(2, 1)
        assertThat(q.ids).containsExactly("b", "c", "a", "d").inOrder()
        assertThat(q.ids[q.index]).isEqualTo("b")
    }

    @Test fun moveUnderShuffleReordersPlayOrderOnly() {
        val q = queue(index = 0)
        q.setShuffle(true)
        val before = q.played()
        q.move(1, 5)
        val expected = before.toMutableList().apply { add(5, removeAt(1)) }
        assertThat(q.played()).isEqualTo(expected)
        assertThat(q.ids).isEqualTo(ten)
        assertThat(q.ids[q.index]).isEqualTo("t0")
    }

    @Test fun removeBeforeCurrentKeepsCurrent() {
        val q = queue(listOf("a", "b", "c"), 2)
        q.remove(0)
        assertThat(q.ids).containsExactly("b", "c").inOrder()
        assertThat(q.ids[q.index]).isEqualTo("c")
    }

    @Test fun removeCurrentMovesToWhatWouldHaveBeenNext() {
        val q = queue(listOf("a", "b", "c"), 1)
        q.remove(1)
        assertThat(q.ids).containsExactly("a", "c").inOrder()
        assertThat(q.ids[q.index]).isEqualTo("c")

        val s = queue(index = 0)
        s.setShuffle(true)
        s.next()
        val wouldBeNext = s.played()[2]
        s.remove(1)                       // the current track, second in play order
        assertThat(s.ids[s.index]).isEqualTo(wouldBeNext)
        assertThat(s.playOrder).containsExactlyElementsIn(0 until 9)
    }

    @Test fun removeLastCurrentTrack() {
        val q = queue(listOf("a", "b", "c"), 2)
        q.remove(2)
        assertThat(q.ids[q.index]).isEqualTo("b")
        val r = queue(listOf("a", "b", "c"), 2).apply { repeat = 1 }
        r.remove(2)
        assertThat(r.ids[r.index]).isEqualTo("a")
        val one = queue(listOf("a"), 0)
        one.remove(0)
        assertThat(one.ids).isEmpty()
        assertThat(one.index).isEqualTo(-1)
        assertThat(one.next()).isNull()
    }

    @Test fun playNextInsertsAfterCurrent() {
        val q = queue(listOf("a", "b", "c"), 0)
        q.playNext(listOf("x", "y"))
        assertThat(q.ids).containsExactly("a", "x", "y", "b", "c").inOrder()
        assertThat(q.index).isEqualTo(0)
        assertThat(q.ids[q.next()!!]).isEqualTo("x")
    }

    @Test fun playNextUnderShuffleInsertsAfterCurrentInPlayOrder() {
        val q = queue(index = 0)
        q.setShuffle(true)
        q.next(); q.next()
        val current = q.ids[q.index]
        val before = q.played()
        q.playNext(listOf("x", "y"))
        val at = before.indexOf(current)
        val expected = before.toMutableList().apply { addAll(at + 1, listOf("x", "y")) }
        assertThat(q.played()).isEqualTo(expected)
        assertThat(q.ids[q.index]).isEqualTo(current)
        assertThat(q.playOrder).containsExactlyElementsIn(0 until 12)
        assertThat(q.ids[q.next()!!]).isEqualTo("x")
        assertThat(q.ids[q.next()!!]).isEqualTo("y")
    }

    @Test fun addAppendsToTheEndOfPlayOrder() {
        val q = queue(index = 0)
        q.setShuffle(true)
        val before = q.played()
        q.add(listOf("x"))
        assertThat(q.played()).isEqualTo(before + "x")
        assertThat(q.ids).isEqualTo(ten + "x")
        assertThat(q.index).isEqualTo(0)
    }

    @Test fun stateRoundTrip() {
        val q = queue(index = 3)
        q.setShuffle(true)
        q.repeat = 1
        q.next()
        val s = q.toState(positionMs = 1_234L, sourceKey = "album:x", now = 99L)
        assertThat(s.positionMs).isEqualTo(1_234L)
        assertThat(s.sourceKey).isEqualTo("album:x")
        assertThat(s.updatedAt).isEqualTo(99L)
        val back = MusicQueue.fromState(s, Random(7))
        assertThat(back.ids).isEqualTo(q.ids)
        assertThat(back.index).isEqualTo(q.index)
        assertThat(back.shuffle).isTrue()
        assertThat(back.playOrder).isEqualTo(q.playOrder)
        assertThat(back.repeat).isEqualTo(1)
        assertThat(back.next()).isEqualTo(q.next())

        val plain = MusicQueue.fromState(queue(listOf("a", "b"), 1).toState(0L, "songs", 0L), Random(7))
        assertThat(plain.ids).containsExactly("a", "b").inOrder()
        assertThat(plain.index).isEqualTo(1)
        assertThat(plain.shuffle).isFalse()
        val empty = MusicQueue.fromState(queue(emptyList(), 0).toState(0L, "songs", 0L), Random(7))
        assertThat(empty.ids).isEmpty()
        assertThat(empty.index).isEqualTo(-1)
    }
}
