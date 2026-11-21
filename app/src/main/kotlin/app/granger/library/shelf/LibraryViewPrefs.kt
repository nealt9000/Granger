package app.granger.library.shelf

import android.content.Context
import android.content.SharedPreferences

/**
 * Each [ShelfKind]'s view and sort, kept across launches in the "library_views" SharedPreferences (the rules are the
 * shared [ShelfChoices], Plan I5). With no [prefs] (tests, previews) the choices last as long as the object.
 */
class LibraryViewPrefs(prefs: SharedPreferences? = null) : ShelfChoices(
    read = { key -> prefs?.getString(key, null) },
    write = { key, value -> prefs?.edit()?.putString(key, value)?.apply() },
) {
    constructor(context: Context) : this(context.getSharedPreferences("library_views", Context.MODE_PRIVATE))

    companion object {
        /** Every shelf's first sort is by title (or name). */
        const val DEFAULT_SORT = ShelfChoices.DEFAULT_SORT
    }
}
