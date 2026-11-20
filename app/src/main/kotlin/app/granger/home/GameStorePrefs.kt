package app.granger.home

import android.content.SharedPreferences

/** [GameStore] in preferences (Android; the iPhone keeps its own). `GameStore.Prefs(prefs)` as before. */
fun GameStore.Companion.Prefs(prefs: SharedPreferences): GameStore = PrefsGameStore(prefs)

class PrefsGameStore(private val prefs: SharedPreferences) : GameStore {
    override var played: Int
        get() = prefs.getInt("played", 0)
        set(v) = prefs.edit().putInt("played", v).apply()
    override var level: Int
        get() = prefs.getInt("order_level", 0)
        set(v) = prefs.edit().putInt("order_level", v).apply()
    override fun best(kind: GameKind) = prefs.getInt("best_${kind.name.lowercase()}", 0)
    override fun setBest(kind: GameKind, score: Int) = prefs.edit().putInt("best_${kind.name.lowercase()}", score).apply()
}
