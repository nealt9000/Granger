package app.granger.widget

import android.content.SharedPreferences
import app.granger.R
import app.granger.UiText
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last model the widget rendered, kept so a new process (a boot update, a tap after the app died) draws that
 * instead of "Nothing playing" before the player has read its saved state. Read back as paused with no second
 * button: in a new process nothing is loaded, and play resumes it. The text is kept as [UiText], not as resolved
 * words, so it follows the language when the widget redraws; see [encode].
 */
class WidgetModelStore(private val prefs: SharedPreferences) {
    fun save(model: WidgetModel) {
        val title = encode(model.title)
        val subtitle = encode(model.subtitle)
        if (title == null || subtitle == null) {
            // Text the store can't keep: drop the copy, so a new process asks the player instead.
            prefs.edit().putBoolean(KEY_SAVED, false).apply()
            return
        }
        prefs.edit()
            .putBoolean(KEY_SAVED, true)
            .putBoolean(KEY_EMPTY, model.empty)
            .putString(KEY_TITLE, title)
            .putString(KEY_SUBTITLE, subtitle)
            .putString(KEY_COVER, model.coverPath)
            .putFloat(KEY_PROGRESS, model.progress ?: -1f)
            .apply()
    }

    fun load(): WidgetModel? {
        if (!prefs.getBoolean(KEY_SAVED, false)) return null
        if (prefs.getBoolean(KEY_EMPTY, true)) return WidgetModel.EMPTY
        val title = decode(prefs.getString(KEY_TITLE, null)) ?: return null
        val subtitle = decode(prefs.getString(KEY_SUBTITLE, null)) ?: return null
        return WidgetModel(
            title, subtitle,
            prefs.getString(KEY_COVER, null), playing = false, secondary = Secondary.NONE,
            progress = prefs.getFloat(KEY_PROGRESS, -1f).takeIf { it >= 0f }, empty = false,
        )
    }

    companion object {
        /**
         * The string resources a [WidgetModel] uses, by a name that stays put: resource ids change from build to
         * build, and the saved copy outlives an update. A resource not listed here isn't saved.
         */
        private val RESOURCES = mapOf(
            "chapter_number" to R.string.player_chapter_number,
            "reading" to R.string.player_reading,
            "finished_reading" to R.string.player_finished_reading,
            "reading_section" to R.string.player_reading_section,
            "finished_reading_section" to R.string.player_finished_reading_section,
        )

        /** [text] as JSON: `{"raw": …}`, or `{"res": name, "args": […]}` with Int or String args; null if it can't be kept. */
        internal fun encode(text: UiText): String? = when (text) {
            is UiText.Raw -> JSONObject().put("raw", text.text).toString()
            is UiText.Res -> {
                val name = RESOURCES.entries.firstOrNull { it.value == text.id }?.key
                val args = text.args.takeIf { a -> a.all { it is Int || it is String } }
                if (name == null || args == null) null else JSONObject().put("res", name).put("args", JSONArray(args)).toString()
            }
            is UiText.Plural -> null
        }

        internal fun decode(json: String?): UiText? = try {
            json?.let(::JSONObject)?.let { o ->
                when {
                    o.has("raw") -> UiText.Raw(o.getString("raw"))
                    else -> RESOURCES[o.optString("res")]?.let { id ->
                        val a = o.getJSONArray("args")
                        UiText.Res(id, *Array(a.length()) { a.get(it) })
                    }
                }
            }
        } catch (e: org.json.JSONException) {
            null  // a copy saved as plain text by an older build: let the player say instead
        }

        const val PREFS = "widget"
        private const val KEY_SAVED = "saved"
        private const val KEY_EMPTY = "empty"
        private const val KEY_TITLE = "title"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_COVER = "cover"
        private const val KEY_PROGRESS = "progress"
    }
}
