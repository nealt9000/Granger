package app.granger.online

import app.granger.core.catalog.PackEntry
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.ManifestCollection
import app.granger.core.library.ManifestItem

/**
 * A book pack's collection read online, before (or without) the pack (Plan BK): its `.gbooks` list, checked against the
 * catalog, and the items in it. Shared since Plan I5 Task 2; [file] is the list's path.
 */
class RemoteCollection(val pack: PackEntry, val file: String, val collection: ManifestCollection, val items: List<ManifestItem>) {
    /** The origin of its books' rows: the ids an installed pack would give them. */
    val origin: BookOrigin get() = if (collection.origin == NAUTILUS) BookOrigin.NAUTILUS else BookOrigin.KIWIX

    /** The library id of [item]: `book:nautilus:<pack name>/<fp>` or `book:kiwix:<pack name>/<key>`. */
    fun bookId(item: ManifestItem): String = BookIds.of(origin, ref(item))

    /** [item]'s ref within its origin. */
    fun ref(item: ManifestItem): String = "${pack.zimName}/${item.key}"

    /** The item whose library id is [bookId], or null. */
    fun itemOf(bookId: String): ManifestItem? = items.firstOrNull { bookId(it) == bookId }

    companion object {
        const val NAUTILUS = "nautilus"
    }
}

