package app.granger.library

import app.granger.core.library.Shelf

// Shared since Plan I5 Task 2: the "Move to…" list; Android's dialogs draw it (OrganizeDialogs.kt).

/** One row of a "Move to…" list. A [Folder] isn't a place to move to; its children follow it, [Existing.indented]. */
sealed interface MoveChoice {
    data class Header(val name: String) : MoveChoice
    data class Folder(val name: String) : MoveChoice
    data class Existing(val id: String, val name: String, val indented: Boolean = false) : MoveChoice
    data class NewCollection(val mediaTypeId: String, val mediaTypeName: String) : MoveChoice
    data object NewMediaType : MoveChoice
}

/**
 * The "Move to collection…" list: each media type's collections, a folder's children indented under it, leaving out
 * [currentCollectionId]; then "New collection…" per type and, if [allowNewMediaType], "New media type…".
 */
fun moveChoices(shelves: List<Shelf>, currentCollectionId: String?, allowNewMediaType: Boolean): List<MoveChoice> = buildList {
    for (shelf in shelves) {
        add(MoveChoice.Header(shelf.mediaType.name))
        for (row in shelf.collections) {
            if (row.isFolder) {
                val children = row.children.filter { it.collection.id != currentCollectionId }
                if (children.isEmpty()) continue
                add(MoveChoice.Folder(row.collection.name))
                children.forEach { add(MoveChoice.Existing(it.collection.id, it.collection.name, indented = true)) }
            } else if (row.collection.id != currentCollectionId) {
                add(MoveChoice.Existing(row.collection.id, row.collection.name))
            }
        }
        add(MoveChoice.NewCollection(shelf.mediaType.id, shelf.mediaType.name))
    }
    if (allowNewMediaType) add(MoveChoice.NewMediaType)
}

