package app.granger.home

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeKind
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

/**
 * The games on the time index (spec §3.3.1, Plan H5c; widened in Plan HY, Neal 2026-11-10): no timer, no model, every
 * answer opens its articles. Home deals one round of any of them at random; the Games screen plays a run of one.
 */
enum class GameKind { ORDER, GUESS, BEFORE_AFTER, OVERLAP, ODD_ONE_OUT, CENTURY }

/** One round: asked, then revealed. */
sealed interface Round {
    val revealed: Boolean
    val kind: GameKind
    /** The articles the round is about, for pictures and for not asking them again soon. */
    val marks: List<TimeMark>

    /** Which came first?: [marks] as shown; [picked] the qids tapped, earliest first. */
    data class Order(override val marks: List<TimeMark>, val picked: List<Long> = emptyList(), override val revealed: Boolean = false) : Round {
        override val kind get() = GameKind.ORDER
        /** The real order, earliest first. */
        val answer: List<TimeMark> get() = marks.sortedBy { it.y0 }
        val right: Boolean get() = picked == answer.map { it.qid }
        /** Years from the first to the last. */
        val gap: Int get() = answer.last().y0 - answer.first().y0
    }

    /** Guess the year: [guess] is where the marker sits, as an astronomical year. */
    data class Guess(val mark: TimeMark, val guess: Int, override val revealed: Boolean = false) : Round {
        override val kind get() = GameKind.GUESS
        override val marks get() = listOf(mark)
        val off: Int get() = abs(guess - mark.y0)
    }

    /** Before or after?: did [mark] come before [anchor], whose year is shown? [said] is the answer given (true = before). */
    data class BeforeAfter(val mark: TimeMark, val anchor: TimeMark, val said: Boolean? = null) : Round {
        override val kind get() = GameKind.BEFORE_AFTER
        override val marks get() = listOf(anchor, mark)
        override val revealed get() = said != null
        val before: Boolean get() = mark.y0 < anchor.y0
        val right: Boolean get() = said == before
        val gap: Int get() = abs(mark.y0 - anchor.y0)
    }

    /** Alive at the same time?: two lifetimes; [said] is the answer given (true = yes). */
    data class Overlap(val a: TimeMark, val b: TimeMark, val said: Boolean? = null, val now: Int = Year.now().value) : Round {
        override val kind get() = GameKind.OVERLAP
        override val marks get() = listOf(a, b)
        override val revealed get() = said != null
        val overlaps: Boolean get() = a.y0 <= b.end(now) && b.y0 <= a.end(now)
        val right: Boolean get() = said == overlaps
        /** Years they shared, or years between one dying and the other being born. */
        val years: Int get() = if (overlaps) minOf(a.end(now), b.end(now)) - maxOf(a.y0, b.y0) else maxOf(a.y0, b.y0) - minOf(a.end(now), b.end(now))
    }

    /** Odd one out: three from about the same time and [odd] from far off; [picked] the one tapped. */
    data class OddOneOut(override val marks: List<TimeMark>, val odd: Long, val picked: Long? = null) : Round {
        override val kind get() = GameKind.ODD_ONE_OUT
        override val revealed get() = picked != null
        val right: Boolean get() = picked == odd
    }

    /** Which century?: [choices] are century indexes (see [centuryOf]), [picked] the one tapped. */
    data class Century(val mark: TimeMark, val choices: List<Int>, val picked: Int? = null) : Round {
        override val kind get() = GameKind.CENTURY
        override val marks get() = listOf(mark)
        override val revealed get() = picked != null
        val answer: Int get() = centuryOf(mark.y0)
        val right: Boolean get() = picked == answer
    }
}

/**
 * A year's century as an index: 0 is the 1st century AD (1–100), 18 the 19th, −1 the 1st century BC, −5 the 5th BC.
 * Years are astronomical (1 BC = 0).
 */
fun centuryOf(year: Int): Int = Math.floorDiv(year - 1, 100)

/** Whether a revealed round counts as right; a guess does when it's anything but further off. */
fun Round.isRight(now: Int): Boolean = when (this) {
    is Round.Order -> right
    is Round.Guess -> closeness(off, mark.y0, now) != Closeness.OFF
    is Round.BeforeAfter -> right
    is Round.Overlap -> right
    is Round.OddOneOut -> right
    is Round.Century -> right
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

/** A run on the Games screen: [size] rounds of one game, [number] the round showing (from 1), [right] so far. */
data class GameRun(val kind: GameKind, val number: Int = 1, val right: Int = 0, val finished: Boolean = false, val best: Int = 0, val newBest: Boolean = false) {
    val size: Int get() = GameModel.RUN
    val last: Boolean get() = number >= size
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
    /** The run being played on the Games screen; null on Home and on the Games screen's list. */
    val run: GameRun? = null,
    /** Each game's best run, out of [GameModel.RUN]. */
    val best: Map<GameKind, Int> = emptyMap(),
)

/** Where rounds played, the level and best runs are kept; in memory for tests and previews. */
interface GameStore {
    var played: Int
    var level: Int
    fun best(kind: GameKind): Int
    fun setBest(kind: GameKind, score: Int)

    class Memory(override var played: Int = 0, override var level: Int = 0) : GameStore {
        private val bests = mutableMapOf<GameKind, Int>()
        override fun best(kind: GameKind) = bests[kind] ?: 0
        override fun setBest(kind: GameKind, score: Int) { bests[kind] = score }
    }

    class Prefs(private val prefs: SharedPreferences) : GameStore {
        override var played: Int
            get() = prefs.getInt("played", 0)
            set(v) = prefs.edit().putInt("played", v).apply()
        override var level: Int
            get() = prefs.getInt("order_level", 0)
            set(v) = prefs.edit().putInt("order_level", v).apply()
        override fun best(kind: GameKind) = prefs.getInt("best_${kind.name.lowercase()}", 0)
        override fun setBest(kind: GameKind, score: Int) = prefs.edit().putInt("best_${kind.name.lowercase()}", score).apply()
    }
}

/**
 * The shared round engine (spec §3.3.1: pick, ask, reveal, open). Home's model is [mixed]: each round is a game picked at
 * random, never the same one twice running. The Games screen's plays runs of one game ([startRun]).
 */
class GameModel(
    private val scope: CoroutineScope,
    private val indexes: suspend () -> TimeIndexes,
    private val picture: suspend (packId: String, path: String) -> String?,
    private val store: GameStore = GameStore.Memory(),
    private val mixed: Boolean = true,
    start: GameKind? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    private val now: Int = Year.now().value,
) {
    private val state = MutableStateFlow(
        GameUi(
            kind = start ?: if (mixed) GameKind.entries.random(random) else GameKind.ORDER,
            played = store.played, level = store.level, latest = now, now = now, best = bests(),
        ),
    )
    val ui: StateFlow<GameUi> = state.asStateFlow()

    private var index: TimeIndexes? = null
    private var started = false
    /** Recently asked articles, so a round doesn't repeat one soon after. */
    private val recent = ArrayDeque<Long>()
    /** A round handed over from Home to start a run with, before the index has loaded. */
    private var pending: Pair<Round, Map<Long, String>>? = null

    private fun bests() = GameKind.entries.associateWith { store.best(it) }.filterValues { it > 0 }

    /** Home deals at once; the Games screen waits on its list until a game is chosen. */
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
            state.update { it.copy(latest = latest, loading = false) }
            val carried = pending
            pending = null
            when {
                carried != null -> show(carried.first, carried.second)
                mixed -> deal(state.value.kind)
                state.value.run != null -> deal(state.value.kind)
            }
        }
    }

    /**
     * Starts a run of [kind] on the Games screen. [carry] is Home's round (with its pictures) when it was still unanswered:
     * it becomes the run's first round, so Continue picks up exactly where Home left off.
     */
    fun startRun(kind: GameKind, carry: Round? = null, pictures: Map<Long, String> = emptyMap()) {
        val c = carry?.takeIf { !it.revealed && it.kind == kind }
        state.update { it.copy(kind = kind, run = GameRun(kind, best = store.best(kind)), round = null) }
        when {
            index == null -> { if (c != null) pending = c to pictures; ensureStarted() }
            c != null -> show(c, pictures)
            else -> scope.launch { deal(kind) }
        }
    }

    /** Back to the Games screen's list. */
    fun endRun() {
        state.update { it.copy(run = null, round = null) }
    }

    /** The next round: of any other game on Home; of the same game in a run, or the score after its last round. */
    fun next() {
        val run = state.value.run
        if (run != null) {
            if (run.finished) return
            if (run.last) {
                val best = store.best(run.kind)
                val newBest = run.right > best
                if (newBest) store.setBest(run.kind, run.right)
                state.update { it.copy(run = run.copy(finished = true, best = maxOf(best, run.right), newBest = newBest), best = bests()) }
                return
            }
            state.update { it.copy(run = run.copy(number = run.number + 1)) }
            scope.launch { deal(run.kind) }
            return
        }
        val kind = if (mixed) otherKind(state.value.kind) else state.value.kind
        state.update { it.copy(kind = kind) }
        scope.launch { deal(kind) }
    }

    /** Takes the round away for another (Home, after its round was carried to the Games screen). */
    fun replace() {
        if (index == null) return
        next()
    }

    private fun otherKind(current: GameKind): GameKind = (GameKind.entries - current).random(random)

    /**
     * Which came first?: tapping a card takes it as the next earliest; tapping a taken card puts it back. When only one
     * is left it goes last by itself and the round reveals. Odd one out: the tap is the answer.
     */
    fun pick(qid: Long) {
        when (val r = state.value.round) {
            is Round.Order -> {
                if (r.revealed) return
                val picked = if (qid in r.picked) r.picked.takeWhile { it != qid } else r.picked + qid
                val full = if (picked.size == r.marks.size - 1) picked + r.marks.first { it.qid !in picked }.qid else picked
                val done = r.copy(picked = full, revealed = full.size == r.marks.size)
                state.update { it.copy(round = done) }
                if (done.revealed) scored(done)
            }
            is Round.OddOneOut -> {
                if (r.revealed || r.marks.none { it.qid == qid }) return
                val done = r.copy(picked = qid)
                state.update { it.copy(round = done) }
                scored(done)
            }
            else -> {}
        }
    }

    /** Before or after? (true = before) and Alive at the same time? (true = yes). */
    fun say(yes: Boolean) {
        val done = when (val r = state.value.round) {
            is Round.BeforeAfter -> if (r.revealed) return else r.copy(said = yes)
            is Round.Overlap -> if (r.revealed) return else r.copy(said = yes)
            else -> return
        }
        state.update { it.copy(round = done) }
        scored(done)
    }

    /** Which century?: the century tapped. */
    fun pickCentury(century: Int) {
        val r = state.value.round as? Round.Century ?: return
        if (r.revealed || century !in r.choices) return
        val done = r.copy(picked = century)
        state.update { it.copy(round = done) }
        scored(done)
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
        val done = r.copy(revealed = true)
        state.update { it.copy(round = done) }
        scored(done)
    }

    /** Counts the round; Which came first? narrows its gap after a right answer and widens it after a wrong one. */
    private fun scored(round: Round) {
        val right = round.isRight(state.value.now)
        store.played = store.played + 1
        if (round is Round.Order) store.level = (store.level + if (right) 1 else -1).coerceIn(0, GAPS.lastIndex)
        state.update { s ->
            s.copy(played = store.played, level = store.level, run = s.run?.let { if (right) it.copy(right = it.right + 1) else it })
        }
    }

    private fun show(round: Round, pictures: Map<Long, String>) {
        round.marks.forEach { remember(it.qid) }
        state.update { it.copy(kind = round.kind, round = round, loading = false, pictures = pictures) }
    }

    /** Deals a round of [kind]; on Home, a game with too few articles on this phone gives way to another. */
    private suspend fun deal(kind: GameKind) {
        val idx = index ?: return
        state.update { it.copy(loading = true) }
        val latest = state.value.latest
        val kinds = if (mixed) listOf(kind) + (GameKind.entries - kind).shuffled(random) else listOf(kind)
        var round: Round? = null
        withContext(io) {
            // Well-known articles first (Neal, 2026-11-08), in any game, before a lesser-known round; a phone with too few
            // of them still gets one.
            run deal@{
                for (minRank in listOf(KNOWN, 0)) for (k in kinds) {
                    round = dealOne(idx, k, latest, minRank)
                    if (round != null) return@deal
                }
            }
        }
        val dealt = round
        dealt?.marks?.forEach { remember(it.qid) }
        state.update { it.copy(kind = dealt?.kind ?: kind, round = dealt, loading = false, pictures = emptyMap()) }
        for (m in dealt?.marks.orEmpty()) {
            val pic = withContext(io) { runCatching { picture(m.packId, m.path) }.getOrNull() } ?: continue
            state.update { if (it.round === dealt) it.copy(pictures = it.pictures + (m.qid to pic)) else it }
        }
    }

    private fun dealOne(idx: TimeIndexes, kind: GameKind, latest: Int, minRank: Int): Round? = when (kind) {
        GameKind.ORDER -> orderRound(idx, latest, minRank)
        GameKind.GUESS -> guessRound(idx, latest, minRank)
        GameKind.BEFORE_AFTER -> beforeAfterRound(idx, latest, minRank)
        GameKind.OVERLAP -> overlapRound(idx, latest, minRank)
        GameKind.ODD_ONE_OUT -> oddRound(idx, latest, minRank)
        GameKind.CENTURY -> centuryRound(idx, latest, minRank)
    }

    /** Playable marks from [from]..[to], well-known enough and not asked lately, shuffled. */
    private fun candidates(idx: TimeIndexes, from: Int, to: Int, latest: Int, minRank: Int, k: Int = POOL): List<TimeMark> =
        idx.top(from, to, emptySet(), k).filter { it.rank >= minRank && playable(it, latest) && it.qid !in recent && it.y0 in from..to }.shuffled(random)

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

    /**
     * Two marks near each other: one shown with its year, the other to place before or after it. The gap scales with how
     * long ago: a few years apart in the 1900s, a few centuries in antiquity.
     */
    private fun beforeAfterRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.BeforeAfter? {
        repeat(TRIES) {
            val y = anchorYear(latest)
            val age = maxOf(10, latest - y)
            val min = maxOf(2, age / 50)
            val max = maxOf(25, age / 3)
            val pool = candidates(idx, y - max, minOf(latest, y + max), latest, minRank)
            for (anchor in pool) {
                val other = pool.firstOrNull { it.qid != anchor.qid && abs(it.y0 - anchor.y0) in min..max } ?: continue
                return Round.BeforeAfter(other, anchor)
            }
        }
        return null
    }

    /**
     * Two people near each other in time, about half the rounds alive together; the others not far apart, so the answer
     * is never obvious from the centuries alone.
     */
    private fun overlapRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.Overlap? {
        repeat(TRIES) {
            val y = anchorYear(latest)
            val reach = maxOf(120, (latest - y) / 6)
            val people = candidates(idx, y - reach, minOf(latest, y + reach), latest, minRank, k = POOL * 3).filter(::lived)
            val wantYes = random.nextBoolean()
            for (a in people) {
                fun fits(b: TimeMark): Boolean {
                    if (b.qid == a.qid) return false
                    val r = Round.Overlap(a, b, now = now)
                    return r.overlaps == wantYes && (r.overlaps || r.years in 1..maxOf(60, (latest - y) / 10))
                }
                val b = people.firstOrNull(::fits) ?: continue
                return Round.Overlap(a, b, now = now)
            }
        }
        return null
    }

    /** A lifetime with both ends, of a believable length (Wikidata has a few with the wrong death year). */
    private fun lived(m: TimeMark): Boolean = m.kind == TimeKind.LIFE && m.y1 != null && (m.y1!! - m.y0) in 15..110 && m.y1!! <= now

    /** Three marks within a span that scales with how long ago, and one from well outside it, before or after. */
    private fun oddRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.OddOneOut? {
        repeat(TRIES) {
            val y = anchorYear(latest)
            val w = maxOf(5, (latest - y) / 12)
            val cluster = candidates(idx, y - w, minOf(latest, y + w), latest, minRank).distinctBy { it.title }.take(3)
            if (cluster.size < 3) return@repeat
            val lo = cluster.minOf { it.y0 }
            val hi = cluster.maxOf { it.y0 }
            val later = hi + 6 * w <= latest && (random.nextBoolean() || lo - 6 * w < TimeScale.HISTORY_START)
            val (from, to) = if (later) (hi + 6 * w) to minOf(latest, hi + 20 * w) else maxOf(TimeScale.HISTORY_START, lo - 20 * w) to (lo - 6 * w)
            if (from > to) return@repeat
            val odd = candidates(idx, from, to, latest, minRank).firstOrNull { o -> cluster.none { it.qid == o.qid } } ?: return@repeat
            return Round.OddOneOut((cluster + odd).shuffled(random), odd.qid)
        }
        return null
    }

    /** One mark and four centuries in a row, the right one anywhere among them, none after this one. */
    private fun centuryRound(idx: TimeIndexes, latest: Int, minRank: Int): Round.Century? {
        repeat(TRIES) {
            val y = anchorYear(latest)
            val mark = candidates(idx, y - 60, minOf(latest, y + 60), latest, minRank).firstOrNull() ?: return@repeat
            val c = centuryOf(mark.y0)
            val top = centuryOf(latest)
            var first = c - random.nextInt(4)
            if (first + 3 > top) first = top - 3
            return Round.Century(mark, (first..first + 3).toList())
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
        /** Rounds in a run on the Games screen. */
        const val RUN = 10
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
    val onPick: (Long) -> Unit = {},
    val onSay: (Boolean) -> Unit = {},
    val onCentury: (Int) -> Unit = {},
    val onGuess: (Int) -> Unit = {},
    val onNudge: (Int) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onNext: () -> Unit = {},
    /** The Games screen: start a run of a game from its list, and go back to the list. */
    val onStart: (GameKind) -> Unit = {},
    val onEndRun: () -> Unit = {},
)

fun gameActions(model: GameModel) = GameActions(
    onShown = model::ensureStarted, onPick = model::pick, onSay = model::say, onCentury = model::pickCentury,
    onGuess = model::guess, onNudge = model::nudge, onSubmit = model::submit, onNext = model::next,
    onStart = { model.startRun(it) }, onEndRun = model::endRun,
)

/** Holds the Games screen's own model (its runs don't change Home's round). */
class GamesViewModel(make: (CoroutineScope) -> GameModel) : androidx.lifecycle.ViewModel() {
    val model: GameModel = make(viewModelScope)
}
