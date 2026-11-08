package app.granger.home

import app.granger.core.library.Lane
import app.granger.core.library.TimeIndexes
import app.granger.core.library.TimeMark
import java.time.Year
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Home timeline shows (Plan H5b, Task 4). */
data class TimelineUi(
    /** False when no pack on the phone has a time index: the section is hidden (Decision 9). */
    val available: Boolean = true,
    val loading: Boolean = true,
    /** Lanes with marks on this phone, in chip order. */
    val lanes: List<Lane> = emptyList(),
    /** Lanes switched on; empty means All. */
    val on: Set<Lane> = emptySet(),
    val window: TimeScale.Window = TimeScale.all(Year.now().value),
    val bounds: TimeScale.Window = TimeScale.all(Year.now().value),
    /** The marks to draw: the highest ranked in the window, plus everything near the lens. */
    val marks: List<TimeMark> = emptyList(),
    val selected: TimeMark? = null,
    /** Marks nearest the selected one in time, closest first (spec §3.1, "Nearby, not just nearest"). */
    val nearby: List<TimeMark> = emptyList(),
    /** The selected article's cached thumbnail, when it has one. */
    val picture: String? = null,
    val now: Int = Year.now().value,
)

class TimelineModel(
    private val scope: CoroutineScope,
    /** Every pack's time index, in Wander's pack order (Decision 5). */
    private val indexes: suspend () -> TimeIndexes,
    /** A cached thumbnail for an article, or null. */
    private val picture: suspend (packId: String, path: String) -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = Random.Default,
    private val now: Int = Year.now().value,
    private val debounceMillis: Long = 60L,
) {
    private val state = MutableStateFlow(TimelineUi(window = TimeScale.all(now), bounds = TimeScale.all(now), now = now))
    val ui: StateFlow<TimelineUi> = state.asStateFlow()

    private var index: TimeIndexes? = null
    private var started = false
    private var query: Job? = null
    private var pictureJob: Job? = null

    /** Loads on the first composition of the section, not at app start. */
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
            val counts = withContext(io) { idx.laneCounts() }
            state.update { it.copy(lanes = Lane.entries.filter { l -> (counts[l] ?: 0) > 0 }) }
            // Open on all of history with the lens on the best-known mark of a random century since 500 BC.
            val century = random.nextInt(-5, 20) * 100
            val first = withContext(io) { idx.marks(century, century + 99, emptySet(), 1, now) }.firstOrNull()
            refresh(state.value.window, immediate = true)
            first?.let { select(it) }
        }
    }

    /** Turns [lane] on or off; with none on, every dated article shows (All). */
    fun toggle(lane: Lane) {
        state.update { it.copy(on = if (lane in it.on) it.on - lane else it.on + lane) }
        reselect()
    }

    fun all() {
        state.update { it.copy(on = emptySet()) }
        reselect()
    }

    /** The visible window changed (a scrub of the ruler or a pinch). */
    fun show(window: TimeScale.Window) {
        state.update { it.copy(window = window) }
        refresh(window)
    }

    /** The lens settled on [year]: select the mark nearest it, preferring a better-known one when two are as near. */
    fun lensAt(year: Double) {
        val idx = index ?: return
        scope.launch {
            val on = state.value.on
            val near = withContext(io) { idx.around(year.toInt(), on, 4) }
            near.minWithOrNull(compareBy<TimeMark> { abs(it.y0 - year) }.thenByDescending { it.rank })?.let { select(it) }
        }
    }

    /** Moves the lens one mark later ([by] = 1) or earlier (-1) in time, through marks in the lanes switched on. */
    fun step(by: Int) {
        val idx = index ?: return
        val cur = state.value.selected ?: return
        scope.launch {
            val list = withContext(io) { neighbourhood(idx, cur, state.value.on) }
            val at = list.indexOfFirst { it.qid == cur.qid }
            if (at >= 0) list.getOrNull(at + by)?.let { select(it, follow = true) }
        }
    }

    /**
     * The selected year in full, with the whole years just before and after it, in time order: a crowded 1945 can't
     * hide a mark from the step buttons or from Nearby.
     */
    private fun neighbourhood(idx: TimeIndexes, cur: TimeMark, on: Set<Lane>): List<TimeMark> {
        val prev = idx.around(cur.y0, on, 1).firstOrNull { it.y0 < cur.y0 }?.y0
        val next = idx.around(cur.y0 + 1, on, 1).firstOrNull { it.y0 > cur.y0 }?.y0
        return listOfNotNull(prev, cur.y0, next)
            .flatMap { y -> idx.marks(y, y, on, YEAR_MAX, now).filter { it.y0 == y } }
            .distinctBy { it.qid }.sortedWith(ORDER)
    }

    fun select(mark: TimeMark) = select(mark, follow = false)

    private fun select(mark: TimeMark, follow: Boolean) {
        val idx = index ?: return
        state.update {
            val w = if (follow && !inside(it.window, mark)) it.window.centredOn(mark.y0.toDouble(), it.bounds) else it.window
            it.copy(selected = mark, picture = null, window = w)
        }
        pictureJob?.cancel()
        pictureJob = scope.launch {
            val near = withContext(io) { neighbourhood(idx, mark, state.value.on) }
                .filter { it.qid != mark.qid }
                .sortedWith(compareBy<TimeMark> { abs(it.y0 - mark.y0) }.thenByDescending { it.rank })
                .take(NEARBY)
            state.update { if (it.selected?.qid == mark.qid) it.copy(nearby = near) else it }
            val pic = withContext(io) { runCatching { picture(mark.packId, mark.path) }.getOrNull() }
            state.update { if (it.selected?.qid == mark.qid) it.copy(picture = pic) else it }
        }
        refresh(state.value.window)
    }

    private fun reselect() {
        val sel = state.value.selected
        refresh(state.value.window, immediate = true)
        if (sel != null) {
            val on = state.value.on
            if (on.isEmpty() || sel.lanes.any { it in on }) select(sel) else lensAt(sel.y0.toDouble())
        }
    }

    private fun refresh(window: TimeScale.Window, immediate: Boolean = false) {
        val idx = index ?: return
        query?.cancel()
        query = scope.launch {
            if (!immediate) delay(debounceMillis)
            val (from, to) = window.years()
            val s = state.value
            val marks = withContext(io) {
                val top = idx.marks(from, to, s.on, LIMIT, now)
                val lens = s.selected?.let { idx.around(it.y0, s.on, 6) }.orEmpty()
                (top + lens).distinctBy { it.qid }
            }
            state.update { it.copy(marks = marks, loading = false) }
        }
    }

    private fun inside(w: TimeScale.Window, m: TimeMark): Boolean {
        val (a, b) = w.years()
        return m.y0 in a..b
    }

    companion object {
        /** At most this many marks in a window (Decision 8). */
        const val LIMIT = 300
        const val NEARBY = 5
        private const val YEAR_MAX = 2000
        val ORDER = compareBy<TimeMark> { it.y0 }.thenByDescending { it.rank }.thenBy { it.qid }
    }
}
