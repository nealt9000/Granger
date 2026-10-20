package app.granger.widget

import android.content.SharedPreferences

/**
 * The last model the widget rendered, kept so a new process (a boot update, a tap after the app died) draws that
 * instead of "Nothing playing" before the player has read its saved state. Read back as paused with no second
 * button: in a new process nothing is loaded, and play resumes it.
 */
class WidgetModelStore(private val prefs: SharedPreferences) {
    fun save(model: WidgetModel) {
        prefs.edit()
            .putBoolean(KEY_SAVED, true)
            .putBoolean(KEY_EMPTY, model.empty)
            .putString(KEY_TITLE, model.title)
            .putString(KEY_SUBTITLE, model.subtitle)
            .putString(KEY_COVER, model.coverPath)
            .putFloat(KEY_PROGRESS, model.progress ?: -1f)
            .apply()
    }

    fun load(): WidgetModel? {
        if (!prefs.getBoolean(KEY_SAVED, false)) return null
        if (prefs.getBoolean(KEY_EMPTY, true)) return WidgetModel.EMPTY
        return WidgetModel(
            prefs.getString(KEY_TITLE, "").orEmpty(), prefs.getString(KEY_SUBTITLE, "").orEmpty(),
            prefs.getString(KEY_COVER, null), playing = false, secondary = Secondary.NONE,
            progress = prefs.getFloat(KEY_PROGRESS, -1f).takeIf { it >= 0f }, empty = false,
        )
    }

    companion object {
        const val PREFS = "widget"
        private const val KEY_SAVED = "saved"
        private const val KEY_EMPTY = "empty"
        private const val KEY_TITLE = "title"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_COVER = "cover"
        private const val KEY_PROGRESS = "progress"
    }
}
