package app.granger.shell

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Research chats and plays taken off the rail with "Remove from rail". Each stays off until it is used again: a chat
 * answered again, an album played again. (Removing a tab closes it and removing a search forgets it, so those need
 * nothing here.)
 */
interface RailHidden {
    /** By rail key ([RailEntry.key]): the item's time ([RailEntry.at]) when it was removed. */
    val hidden: StateFlow<Map<String, Long>>

    /**
     * When Clear history last ran; 0 before. Nothing unpinned from before it comes back to the rail or Continue, so a
     * removed item is never backfilled from what was cleared (Plan U3b, Neal's walkthrough).
     */
    val clearedAt: StateFlow<Long>

    fun hide(key: String, at: Long)

    /** Clear history at [at]: moves the cut-off there and forgets what was hidden, which is all older. */
    fun clear(at: Long)
}

/** Whether [entry] was removed from the rail and hasn't been used since. */
internal fun Map<String, Long>.hides(entry: RailEntry): Boolean = this[entry.key]?.let { entry.at <= it } == true

class SharedRailHidden(private val prefs: SharedPreferences) : RailHidden {
    constructor(context: Context) : this(context.getSharedPreferences("rail_hidden", Context.MODE_PRIVATE))

    private val state = MutableStateFlow(
        prefs.all.filterKeys { it != CLEARED_AT }.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap(),
    )
    private val cleared = MutableStateFlow(prefs.getLong(CLEARED_AT, 0L))

    override val hidden: StateFlow<Map<String, Long>> = state
    override val clearedAt: StateFlow<Long> = cleared

    override fun hide(key: String, at: Long) {
        // The oldest go first past the cap; an item that old is long off the rail anyway.
        val kept = (state.value + (key to at)).entries.sortedByDescending { it.value }.take(MAX_HIDDEN).associate { it.key to it.value }
        prefs.edit().clear().apply {
            kept.forEach { (k, v) -> putLong(k, v) }
            putLong(CLEARED_AT, cleared.value)
        }.apply()
        state.value = kept
    }

    override fun clear(at: Long) {
        prefs.edit().clear().putLong(CLEARED_AT, at).apply()
        state.value = emptyMap()
        cleared.value = at
    }

    private companion object {
        const val MAX_HIDDEN = 100

        /** Not a rail key: those all have a kind prefix ("chat:", "play:"). */
        const val CLEARED_AT = "cleared_at"
    }
}
