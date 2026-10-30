package app.granger.library.shelf

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The kinds of shelf whose view and sort are remembered (Plan LV Decision 5). Every book collection shares [BOOKS], so
 * Classics and My books look alike.
 */
enum class ShelfKind(val id: String) { BOOKS("books"), SONGS("songs"), ALBUMS("albums"), ARTISTS("artists"), AUDIOBOOKS("audiobooks") }

/** How a shelf shows its items: an A–Z list (the default everywhere) or a cover grid. */
enum class ShelfView { LIST, GRID }

/**
 * Each [ShelfKind]'s view and sort, kept across launches in the "library_views" SharedPreferences. A sort is its enum's
 * name ([BookSort], `SongSort`, …); each shelf maps it back and falls back to its default for an unknown one. With no
 * [prefs] (tests, previews) the choices last as long as the object.
 */
class LibraryViewPrefs(private val prefs: SharedPreferences? = null) {
    constructor(context: Context) : this(context.getSharedPreferences("library_views", Context.MODE_PRIVATE))

    private val views = ShelfKind.entries.associateWith { kind ->
        MutableStateFlow(prefs?.getString(viewKey(kind), null)?.let { s -> ShelfView.entries.firstOrNull { it.name == s } } ?: ShelfView.LIST)
    }
    private val sorts = ShelfKind.entries.associateWith { kind -> MutableStateFlow(prefs?.getString(sortKey(kind), null) ?: DEFAULT_SORT) }

    /** [ShelfView.LIST] until changed. */
    fun view(kind: ShelfKind): StateFlow<ShelfView> = views.getValue(kind)

    fun setView(kind: ShelfKind, view: ShelfView) {
        prefs?.edit()?.putString(viewKey(kind), view.name)?.apply()
        views.getValue(kind).value = view
    }

    /** The sort's name, [DEFAULT_SORT] (Title) until changed. */
    fun sort(kind: ShelfKind): StateFlow<String> = sorts.getValue(kind)

    fun setSort(kind: ShelfKind, sort: String) {
        prefs?.edit()?.putString(sortKey(kind), sort)?.apply()
        sorts.getValue(kind).value = sort
    }

    companion object {
        /** Every shelf's first sort is by title (or name). */
        const val DEFAULT_SORT = "TITLE"

        private fun viewKey(kind: ShelfKind) = "${kind.id}.view"
        private fun sortKey(kind: ShelfKind) = "${kind.id}.sort"
    }
}

/** A stored sort [name] as one of [E]'s entries; [default] for a name it doesn't have. */
inline fun <reified E : Enum<E>> sortNamed(name: String, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default
