package app.granger.explore

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Explore keeps track of having been in: rooms, subjects and eras only, never articles. */
enum class SeenKind { ROOM, SUBJECT, ERA }

/**
 * Where the reader has been in Explore (Plan EX round 2, R: the quiet game layer): whether it is kept at all ([on]), and
 * for each place when it was first seen ([first], by `kind:id`, in milliseconds).
 */
data class Seen(val on: Boolean = true, val first: Map<String, Long> = emptyMap()) {
    fun has(kind: SeenKind, id: String): Boolean = key(kind, id) in first

    /** The ids of [kind] seen; null when nothing is kept, so nothing is shown. */
    fun ids(kind: SeenKind): Set<String>? {
        if (!on) return null
        val prefix = kind.name.lowercase() + ":"
        return first.keys.filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
    }

    companion object {
        fun key(kind: SeenKind, id: String) = "${kind.name.lowercase()}:$id"
    }
}

/**
 * Keeps [Seen] on this phone. A place is marked the first time it is opened and never again; turned off, nothing more
 * is marked and nothing is shown, and what was kept stays until it is cleared.
 */
interface SeenStore {
    val state: StateFlow<Seen>
    fun mark(kind: SeenKind, id: String)
    fun setOn(on: Boolean)
    fun clear()

    class Memory(start: Seen = Seen(), private val now: () -> Long = System::currentTimeMillis) : SeenStore {
        private val seen = MutableStateFlow(start)
        override val state: StateFlow<Seen> = seen.asStateFlow()
        override fun mark(kind: SeenKind, id: String) {
            val s = seen.value
            if (s.on && !s.has(kind, id)) seen.value = s.copy(first = s.first + (Seen.key(kind, id) to now()))
        }
        override fun setOn(on: Boolean) { seen.value = seen.value.copy(on = on) }
        override fun clear() { seen.value = seen.value.copy(first = emptyMap()) }
    }

    /** In a preferences file of its own: a hundred-odd small entries, read once. */
    class Prefs(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) : SeenStore {
        private val seen = MutableStateFlow(
            Seen(prefs.getBoolean(ON, true), prefs.all.filter { (k, v) -> k != ON && v is Long }.mapValues { it.value as Long }),
        )
        override val state: StateFlow<Seen> = seen.asStateFlow()

        @Synchronized override fun mark(kind: SeenKind, id: String) {
            val s = seen.value
            if (!s.on || s.has(kind, id)) return
            val at = now()
            prefs.edit().putLong(Seen.key(kind, id), at).apply()
            seen.value = s.copy(first = s.first + (Seen.key(kind, id) to at))
        }

        @Synchronized override fun setOn(on: Boolean) {
            prefs.edit().putBoolean(ON, on).apply()
            seen.value = seen.value.copy(on = on)
        }

        @Synchronized override fun clear() {
            val on = seen.value.on
            prefs.edit().clear().putBoolean(ON, on).apply()
            seen.value = Seen(on)
        }

        private companion object {
            const val ON = "on"
        }
    }
}
