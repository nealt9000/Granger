package app.granger.shell

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The rail's four recent items, in the order they arrived (Plan U3b Decision 6): [keys] top first, by rail key
 * ([RailEntry.key]). [seenAt] is the newest [RailEntry.at] the rail has seen, so an item newer than it has just arrived;
 * one opened again that's already in the four stays where it is.
 */
data class RailFour(val keys: List<String>, val seenAt: Long) {
    /**
     * The four after [candidates] (the unpinned, unhidden items, newest first). Items gone from them leave. What arrived
     * since [seenAt] enters at the top, oldest first so the newest ends on top, and the bottom drops past [MAX]. A slot
     * left free is filled at the bottom by the newest item not in the four, so nothing shown moves.
     */
    fun next(candidates: List<RailEntry>): RailFour {
        val present = candidates.mapTo(HashSet()) { it.key }
        var kept = keys.filter { it in present }
        candidates.filter { it.at > seenAt && it.key !in kept }.asReversed().forEach { kept = (listOf(it.key) + kept).take(MAX) }
        if (kept.size < MAX) kept = kept + candidates.map { it.key }.filter { it !in kept }.take(MAX - kept.size)
        return RailFour(kept, maxOf(seenAt, candidates.maxOfOrNull { it.at } ?: seenAt))
    }

    companion object {
        const val MAX = 4

        /** The first run: today's ordering, the newest four, so a rail from before keeps what it showed. */
        fun seed(candidates: List<RailEntry>): RailFour =
            RailFour(candidates.sortedByDescending { it.at }.take(MAX).map { it.key }, candidates.maxOfOrNull { it.at } ?: 0L)
    }
}

/** Where the rail keeps its [RailFour] between runs. */
interface RailOrder {
    /** Null until the first run seeds it. */
    val four: StateFlow<RailFour?>

    fun save(four: RailFour)
}

class SharedRailOrder(private val prefs: SharedPreferences) : RailOrder {
    constructor(context: Context) : this(context.getSharedPreferences("rail_order", Context.MODE_PRIVATE))

    private val state = MutableStateFlow(
        if (!prefs.contains(SEEN_AT)) {
            null
        } else {
            RailFour((0 until RailFour.MAX).mapNotNull { prefs.getString("$KEY$it", null) }, prefs.getLong(SEEN_AT, 0L))
        },
    )

    override val four: StateFlow<RailFour?> = state

    override fun save(four: RailFour) {
        prefs.edit().clear().apply {
            four.keys.forEachIndexed { i, key -> putString("$KEY$i", key) }
            putLong(SEEN_AT, four.seenAt)
        }.apply()
        state.value = four
    }

    private companion object {
        const val KEY = "key"
        const val SEEN_AT = "seen_at"
    }
}
