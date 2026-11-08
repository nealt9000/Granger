package app.granger.home

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import java.time.Year
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The games on the time index (spec §3.3.1, Plan H5c): no timer, no model, every answer opens its articles. */
enum class GameKind { ORDER, GUESS }

/** One round: asked, then revealed. */
sealed interface Round {
    val revealed: Boolean

    /** Which came first?: [marks] as shown; [picked] the qids tapped, earliest first. */
    data class Order(val marks: List<TimeMark>, val picked: List<Long> = emptyList(), override val revealed: Boolean = false) : Round {
        /** The real order, earliest first. */
        val answer: List<TimeMark> get() = marks.sortedBy { it.y0 }
        val right: Boolean get() = picked == answer.map { it.qid }
        /** Years from the first to the last. */
        val gap: Int get() = answer.last().y0 - answer.first().y0
    }

    /** Guess the year: [guess] is where the marker sits, as an astronomical year. */
    data class Guess(val mark: TimeMark, val guess: Int, override val revealed: Boolean = false) : Round {
        val off: Int get() = abs(guess - mark.y0)
    }
}

/** How near a guess was, by how long ago the answer is: two years off in 1945 count like sixty in 500 BC. */
enum class Closeness { SPOT_ON, VERY_CLOSE, CLOSE, OFF }

fun closeness(off: Int, answer: Int, now: Int): Closeness {
    val tolerance = maxOf(1, (now - answer) / 40)
    return when {
        off == 0 -> Closeness.SPOT_ON
        off <= tolerance -> Closeness.VERY_CLOSE
        off <= tolerance * 4 -> Closeness.CLOSE
        else -> Closeness.OFF
    }
}

data class GameUi(
    /** False when no pack on the phone has a time index: Play is hidden, like the timeline. */
    val available: Boolean = true,
    val loading: Boolean = true,
    val kind: GameKind = GameKind.ORDER,
    val round: Round? = null,
    /** Cached thumbnails by qid, as they arrive. */
    val pictures: Map<Long, String> = emptyMap(),
    /** Rounds played on this phone, all games: kept locally, never sent (spec §3.3). */
    val played: Int = 0,
    /** Which came first?'s level: higher is a narrower gap. */
    val level: Int = 0,
    val latest: Int = Year.now().value,
    val now: Int = Year.now().value,
)

/** Where rounds played and the level are kept; in memory for tests and previews. */
interface GameStore {
    var played: Int
    var level: Int

    class Memory(override var played: Int = 0, override var level: Int = 0) : GameStore

    class Prefs(private val prefs: SharedPreferences) : GameStore {
        override var played: Int
            get() = prefs.getInt("played", 0)
            set(v) = prefs.edit().putInt("played", v).apply()
        override var level: Int
            get() = prefs.getInt("order_level", 0)
            set(v) = prefs.edit().putInt("order_level", v).apply()
    }
}

/**
 * The shared round engine (spec §3.3.1: pick, ask, reveal, open). Home's model [alternates] between the two games, a
 * round of each in turn; the Games screen's keeps to the one chosen.
 */
class GameModel(
    private val scope: CoroutineScope,
    private val indexes: suspend () -> TimeIndexes,
    private val picture: suspend (packId: String, path: String) -> String?,
    private val store: GameStore = GameStore.Memory(),
    private val alternates: Boolean = true,
    start: GameKind = GameKind.ORDER,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    private val now: Int = Year.now().value,
) {
    private val state = MutableStateFlow(GameUi(kind = start, played = store.played, level = store.level, latest = now, now = now))
    val ui: StateFlow<GameUi> = state.asStateFlow()

    private var index: TimeIndexes? = null
    private var started = false
    /** Recently asked articles, so a round doesn't repeat one soon after. */
    private val recent = ArrayDeque<Long>()

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            val idx = withContext(io) { indexes() }
            index = idx
            if (idx.isEmpty) {
                state.update { it.copy(available = false, loading = false) }
                return@launch
            }
            val latest = withContext(io) { (idx.latestYear() ?: now).coerceAtMost(now) }
            state.update { it.copy(latest = latest) }
            deal(state.value.kind)
        }
    }

    /** Switches game (the Games screen's tabs); a new round of it. */
    fun choose(kind: GameKind) {
        if (kind == state.value.kind && state.value.round?.revealed == false) return
        state.update { it.copy(kind = kind) }
        if (index != null) scope.launch { deal(kind) }
    }

    /** A new round: of the other game on Home, the same one on the Games screen. */
    fun next() {
        val kind = if (alternates) GameKind.entries[(state.value.kind.ordinal + 1) % GameKind.entries.size] else state.value.kind
        state.update { it.copy(kind = kind) }
        scope.launch { deal(kind) }
    }

    /**
     * Which came first?: tapping a card takes it as the next earliest; tapping a taken card puts it back. When only one
     * is left it goes last by itself and the round reveals.
     */
    fun pick(qid: Long) {
        val r = state.value.round as? Round.Order ?: return
        if (r.revealed) return
        val picked = if (qid in r.picked) r.picked.takeWhile { it != qid } else r.picked + qid
        val full = if (picked.size == r.marks.size - 1) picked + r.marks.first { it.qid !in picked }.qid else picked
        val done = full.size == r.marks.size
        state.update { it.copy(round = r.copy(picked = full, revealed = done)) }
        if (done) scored(right = r.copy(picked = full).right)
    }

    /** Guess the year: the marker moved to [year]. */
    fun guess(year: Int) {
        val r = state.value.round as? Round.Guess ?: return
        if (r.revealed) return
        state.update { it.copy(round = r.copy(guess = year.coerceIn(TimeScale.HISTORY_START, it.latest))) }
    }

    fun nudge(by: Int) {
        val r = state.value.round as? Round.Guess ?: return
        guess(r.guess + by)
    }

    fun submit() {
        val r = state.value.round as? Round.Guess ?: return
        if (r.revealed) return
        state.update { it.copy(round = r.copy(revealed = true)) }
        scored(right = null)
    }

    /** Counts the round; Which came first? narrows its gap after a right answer and widens it after a wrong one. */
    private fun scored(right: Boolean?) {
        store.played = store.played + 1
        if (right != null) store.level = (store.level + if (right) 1 else -1).coerceIn(0, GAPS.lastIndex)
        state.update { it.copy(played = store.played, level = store.level) }
    }

    private suspend fun deal(kind: GameKind) {
        val idx = index ?: return
        state.update { it.copy(loading = true) }
        val latest = state.value.latest
        val round = withContext(io) {
            when (kind) {
                // Well-known articles first (Neal, 2026-11-08); a phone with too few of them still gets a round.
                GameKind.ORDER -> orderRound(idx, latest, KNOWN) ?: orderRound(idx, latest, 0)
                GameKind.GUESS -> guessRound(idx, latest, KNOWN) ?: guessRound(idx, latest, 0)
            }
        }
        val marks = when (round) {
            is Round.Order -> round.marks
            is Round.Guess -> listOf(round.mark)
            null -> emptyList()
        }
        marks.forEach { remember(it.qid) }
        state.update { it.copy(round = round, loading = false, pictures = emptyMap()) }
        for (m in marks) {
            val pic = withContext(io) { runCatching { picture(m.packId, m.path) }.getOrNull() } ?: continue
            state.update { if (it.round === round) it.copy(pictures = it.pictures + (m.qid to pic)) else it }
        }
    }

    /** Two cards, or three from level 2; their years at least a third of the level's gap apart and at most twice it. */
    private fun orderRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.Order? {
        val level = store.level.coerceIn(0, GAPS.lastIndex)
        val gap = GAPS[level]
        val n = if (level >= 2) 3 else 2
        val min = maxOf(1, gap / 3)
        repeat(TRIES) {
            val anchor = anchorYear(latest)
            val pool = idx.top(anchor - gap * 2, anchor + gap * 2, emptySet(), POOL).filter { it.rank >= minRank && playable(it, latest) && it.qid !in recent }.shuffled(random)
            for (first in pool) {
                val chosen = mutableListOf(first)
                for (c in pool) {
                    if (chosen.size == n) break
                    if (chosen.all { abs(it.y0 - c.y0) >= min } && (chosen + c).let { s -> s.maxOf { it.y0 } - s.minOf { it.y0 } } <= gap * 2) chosen += c
                }
                if (chosen.size == n) return Round.Order(chosen.shuffled(random))
            }
        }
        return null
    }

    /** The best-known playable mark near a random point in recorded history; the marker starts in the middle. */
    private fun guessRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.Guess? {
        repeat(TRIES) {
            val anchor = anchorYear(latest)
            val mark = idx.top(anchor - 60, anchor + 60, emptySet(), POOL).firstOrNull { it.rank >= minRank && playable(it, latest) && it.qid !in recent }
            if (mark != null) {
                val w = TimeScale.history(latest)
                return Round.Guess(mark, TimeScale.toYear((w.start + w.end) / 2).toInt())
            }
        }
        return null
    }

    /** A random point on the timeline's own axis from 3000 BC, so recent centuries come up more, as they fill the river. */
    private fun anchorYear(latest: Int): Int {
        val w = TimeScale.history(latest)
        return TimeScale.toYear(w.start + random.nextDouble() * w.span).toInt()
    }

    private fun remember(qid: Long) {
        recent.addLast(qid)
        while (recent.size > RECENT) recent.removeFirst()
    }

    companion object {
        /** Which came first?'s gap in years by level: rounds get harder by narrowing the gap, not by a clock (spec §3.3). */
        val GAPS = listOf(500, 200, 80, 30, 10)
        private const val TRIES = 12
        private const val POOL = 60
        /**
         * The rank a round prefers: Battle of Hastings is 81, Heraclitus 147, Abarsal 10. About 8,000 of the phone's
         * marks clear it (Top 7,801, History 704; measured 2026-11-08).
         */
        const val KNOWN = 60
        private const val RECENT = 40
        private val GIVEAWAY = Regex("\\d{3,4}|century|millennium|siglo|milenio", RegexOption.IGNORE_CASE)
        private val LISTS = listOf("List of", "Lists of", "Lista de", "Anexo:", "Timeline of", "Cronología")

        /** Dated to the year, in recorded history and not after today, and its title doesn't give the year away. */
        fun playable(m: TimeMark, latest: Int): Boolean =
            m.prec >= 9 && m.y0 in TimeScale.HISTORY_START..latest && !GIVEAWAY.containsMatchIn(m.title) && LISTS.none { m.title.startsWith(it) }
    }
}

/** What the games' controls do; [onShown] starts the model the first time it's shown. */
class GameActions(
    val onShown: () -> Unit = {},
    val onChoose: (GameKind) -> Unit = {},
    val onPick: (Long) -> Unit = {},
    val onGuess: (Int) -> Unit = {},
    val onNudge: (Int) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onNext: () -> Unit = {},
)

fun gameActions(model: GameModel) = GameActions(
    onShown = model::ensureStarted, onChoose = model::choose, onPick = model::pick, onGuess = model::guess,
    onNudge = model::nudge, onSubmit = model::submit, onNext = model::next,
)

/** Holds the Games screen's own model (its game choice doesn't change Home's round). */
class GamesViewModel(make: (CoroutineScope) -> GameModel) : androidx.lifecycle.ViewModel() {
    val model: GameModel = make(viewModelScope)
}
