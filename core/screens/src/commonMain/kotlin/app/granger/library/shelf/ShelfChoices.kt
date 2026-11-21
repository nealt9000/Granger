package app.granger.library.shelf

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
 * Each [ShelfKind]'s view and sort, kept across launches where [read] and [write] keep them (Android: the
 * "library_views" SharedPreferences, `LibraryViewPrefs`). A sort is its enum's name ([app.granger.library.BookSort],
 * `SongSort`, …); each shelf maps it back and falls back to its default for an unknown one. With nothing kept (tests,
 * previews) the choices last as long as the object. Shared since Plan I5 Task 2.
 */
open class ShelfChoices(
    private val read: (String) -> String? = { null },
    private val write: (String, String) -> Unit = { _, _ -> },
) {
    private val views = ShelfKind.entries.associateWith { kind ->
        MutableStateFlow(read(viewKey(kind))?.let { s -> ShelfView.entries.firstOrNull { it.name == s } } ?: ShelfView.LIST)
    }
    private val sorts = ShelfKind.entries.associateWith { kind -> MutableStateFlow(read(sortKey(kind)) ?: DEFAULT_SORT) }

    /** [ShelfView.LIST] until changed. */
    fun view(kind: ShelfKind): StateFlow<ShelfView> = views.getValue(kind)

    fun setView(kind: ShelfKind, view: ShelfView) {
        write(viewKey(kind), view.name)
        views.getValue(kind).value = view
    }

    /** The sort's name, [DEFAULT_SORT] (Title) until changed. */
    fun sort(kind: ShelfKind): StateFlow<String> = sorts.getValue(kind)

    fun setSort(kind: ShelfKind, sort: String) {
        write(sortKey(kind), sort)
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

/** The other view. */
fun ShelfView.toggled(): ShelfView = if (this == ShelfView.LIST) ShelfView.GRID else ShelfView.LIST
