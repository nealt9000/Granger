package app.granger.core.player

import android.content.SharedPreferences
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao

/** Android's [PlayerPrefs]: the player's SharedPreferences file, as the saved-modes reader read it before Plan I6. */
class SharedPlayerPrefs(private val prefs: SharedPreferences) : PlayerPrefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun listen(onChange: (key: String?) -> Unit): () -> Unit {
        // SharedPreferences holds listeners weakly: the returned function keeps this one alive while registered.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

/** The shared reader over the player's SharedPreferences (the engine's and the controller hub's form). */
internal fun SavedModesReader(media: MediaLibrary, playback: PlaybackDao, prefs: SharedPreferences): SavedModesReader =
    SavedModesReader(media, playback, SharedPlayerPrefs(prefs))

internal suspend fun lastAudiobookId(prefs: SharedPreferences, states: List<AudiobookState>, media: MediaLibrary): String? =
    lastAudiobookId(SharedPlayerPrefs(prefs), states, media)
