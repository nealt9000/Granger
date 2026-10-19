package app.granger.core.player

import app.granger.core.library.MusicState
import kotlin.random.Random

/**
 * The music queue: what plays, in which order, and what comes next (spec §6). Pure logic; the engine mirrors it into
 * one ExoPlayer playlist.
 *
 * Two orders are kept:
 * - [ids], the original order (what the queue was started with, plus edits).
 * - [playOrder], the order things play in: a permutation of the indices of [ids]. It is the identity when [shuffle]
 *   is off.
 *
 * [index], and what [next] and [previous] return, are indices into [ids]. [move] and [remove] take positions in
 * [playOrder], which is what the queue screen shows; with shuffle off the two are the same.
 *
 * [repeat]: 0 off, 1 all, 2 one (as stored in [MusicState.repeatMode]). An empty queue has [index] -1.
 */
class MusicQueue(ids: List<String>, index: Int, private val random: Random) {
    private val _ids = ids.toMutableList()
    private val order = _ids.indices.toMutableList()

    /** Position of the current track in [order]; -1 when empty. */
    private var pos = if (_ids.isEmpty()) -1 else index.coerceIn(_ids.indices)

    val ids: List<String> get() = _ids

    /** The current track's index in [ids], or -1 when the queue is empty. */
    val index: Int get() = if (pos < 0) -1 else order[pos]

    /** The play order, as indices into [ids]. A snapshot. */
    val playOrder: List<Int> get() = order.toList()

    /** Setting this is [setShuffle]. */
    var shuffle: Boolean
        get() = shuffleState
        set(on) = setShuffle(on)
    private var shuffleState = false

    var repeat: Int = 0
        set(value) {
            require(value in 0..2) { "repeat is 0 (off), 1 (all) or 2 (one), not $value" }
            field = value
        }

    /**
     * Moves to the track that plays after the current one and returns its index in [ids], or null at the end with
     * repeat off (the queue then stays on the last track). This is the automatic-advance rule: repeat one returns the
     * current index, and repeat all wraps to the start of the same play order (a shuffled queue is not re-shuffled on
     * wrap, so a saved order stays valid and each pass is the same).
     */
    fun next(): Int? {
        if (pos < 0) return null
        when {
            repeat == 2 -> Unit
            pos + 1 < order.size -> pos++
            repeat == 1 -> pos = 0
            else -> return null
        }
        return index
    }

    /**
     * The track to play for a "previous" press, made current. After 3 s into the track ([positionMs] > 3000) it
     * restarts the current track (returns the current index). Otherwise it steps back in play order; at the start it
     * wraps to the end with repeat all, and stays on the current track otherwise (repeat off or one).
     */
    fun previous(positionMs: Long): Int {
        if (pos < 0) return -1
        if (positionMs > RESTART_AFTER_MS) return index
        when {
            pos > 0 -> pos--
            repeat == 1 -> pos = order.lastIndex
        }
        return index
    }

    /**
     * Makes the item at [index] in [ids] current without changing either order. The engine calls it when the player
     * has moved on by itself (a transition, a Next press), so the queue follows the player.
     */
    fun select(index: Int) {
        require(index in _ids.indices) { "select($index) outside 0..${_ids.lastIndex}" }
        pos = order.indexOf(index)
    }

    /**
     * On: the current track stays current and goes first in the play order; the rest follow in a random order drawn
     * from [random]. Turning it on again re-shuffles. Off: back to the original order, at the current track.
     */
    @JvmName("applyShuffle") // the plan's API has both `var shuffle` and setShuffle, which clash on the JVM
    fun setShuffle(on: Boolean) {
        val current = index
        order.clear()
        if (on && current >= 0) {
            order += current
            order += _ids.indices.filter { it != current }.shuffled(random)
            pos = 0
        } else {
            order += _ids.indices
            pos = current
        }
        shuffleState = on && current >= 0
    }

    /**
     * Moves the item at play-order position [from] to [to]. With shuffle off this reorders [ids]; with shuffle on it
     * reorders only the play order, leaving the original order alone. The current track stays current.
     */
    fun move(from: Int, to: Int) {
        require(from in order.indices && to in order.indices) { "move($from, $to) outside 0..${order.lastIndex}" }
        if (from == to) return
        val current = index
        if (shuffleState) {
            order.add(to, order.removeAt(from))
            pos = order.indexOf(current)
        } else {
            val id = _ids.removeAt(from)
            _ids.add(to, id)
            pos = when (current) {
                from -> to
                in (from + 1)..to -> current - 1
                in to until from -> current + 1
                else -> current
            }
        }
    }

    /**
     * Removes the item at play-order position [i]. Removing the current track makes current what would have played
     * next; if it was last, the queue wraps to the first item with repeat all and otherwise stays on the new last item.
     */
    fun remove(i: Int) {
        require(i in order.indices) { "remove($i) outside 0..${order.lastIndex}" }
        val removed = order.removeAt(i)
        _ids.removeAt(removed)
        for (k in order.indices) if (order[k] > removed) order[k]--
        pos = when {
            order.isEmpty() -> -1
            i < pos -> pos - 1
            i > pos -> pos
            i < order.size -> i
            repeat == 1 -> 0
            else -> order.lastIndex
        }
    }

    /**
     * Inserts [newIds] to play right after the current track, in their given order. In [ids] they go right after the
     * current track too, so they stay next if shuffle is turned off. An empty queue simply takes them, starting at the
     * first.
     */
    fun playNext(newIds: List<String>) {
        if (newIds.isEmpty()) return
        if (pos < 0) return add(newIds)
        val at = index + 1
        _ids.addAll(at, newIds)
        for (k in order.indices) if (order[k] >= at) order[k] += newIds.size
        order.addAll(pos + 1, newIds.indices.map { at + it })
    }

    /** Appends [newIds] to the end of both orders. An empty queue starts at the first of them. */
    fun add(newIds: List<String>) {
        if (newIds.isEmpty()) return
        val start = _ids.size
        _ids.addAll(newIds)
        order.addAll(newIds.indices.map { start + it })
        if (pos < 0) pos = order.indexOf(start)
    }

    /** The state to save. [MusicState.shuffledOrder] holds the play order only when shuffle is on, else "". */
    fun toState(positionMs: Long, sourceKey: String, now: Long): MusicState = MusicState(
        queue = _ids.joinToString("\n"),
        index = index,
        positionMs = positionMs,
        shuffle = shuffleState,
        shuffledOrder = if (shuffleState) order.joinToString("\n") else "",
        repeatMode = repeat,
        sourceKey = sourceKey,
        updatedAt = now,
    )

    companion object {
        private const val RESTART_AFTER_MS = 3_000L

        /**
         * Rebuilds a saved queue. A saved shuffle order that isn't a permutation of the queue (a damaged row) is
         * replaced by a fresh shuffle from [random], current track first; an out-of-range index is clamped.
         */
        fun fromState(s: MusicState, random: Random): MusicQueue {
            val ids = if (s.queue.isEmpty()) emptyList() else s.queue.split("\n")
            val q = MusicQueue(ids, s.index, random)
            q.repeat = s.repeatMode.takeIf { it in 0..2 } ?: 0
            if (s.shuffle && ids.isNotEmpty()) {
                val saved = s.shuffledOrder.split("\n").mapNotNull { it.toIntOrNull() }
                if (saved.sorted() == ids.indices.toList()) {
                    val current = q.index
                    q.order.clear()
                    q.order += saved
                    q.pos = q.order.indexOf(current)
                    q.shuffleState = true
                } else {
                    q.setShuffle(true)
                }
            }
            return q
        }
    }
}
