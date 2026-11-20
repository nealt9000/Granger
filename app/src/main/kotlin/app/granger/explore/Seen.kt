package app.granger.explore

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [SeenStore] in a preferences file of its own (Android; the iPhone keeps its own). `SeenStore.Prefs(prefs)` as before. */
fun SeenStore.Companion.Prefs(prefs: SharedPreferences, now: () -> Long = System::currentTimeMillis): SeenStore = PrefsSeenStore(prefs, now)

/** In a preferences file of its own: a hundred-odd small entries, read once. */
/** In a preferences file of its own: a hundred-odd small entries, read once. */
class PrefsSeenStore(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) : SeenStore {
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
