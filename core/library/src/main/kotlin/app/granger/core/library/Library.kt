package app.granger.core.library

import app.granger.core.zim.ZimArchive
import kotlinx.coroutines.flow.Flow

/** Installed packs, bookmarks and reading positions. The app's screens depend only on this. */
interface Library {
    /** Every known pack, including missing and damaged ones, sorted by title. */
    val packs: Flow<List<Pack>>

    /** Newest first. */
    val bookmarks: Flow<List<Bookmark>>

    /** Re-scans the pack folders on every volume and updates [packs]. */
    suspend fun refresh()

    /** A verified file was written at [path]; drop any open archive for it so the next read opens the new file. */
    suspend fun fileReplaced(path: String)

    /**
     * The open archive for an OK pack, or null when the pack is unknown, missing, damaged, or fails
     * to open. Blocking: call it off the main thread.
     */
    fun archive(packId: String): ZimArchive?

    /** The archive only if it is already open; never touches the database or disk. Safe on the main thread. */
    fun cachedArchive(packId: String): ZimArchive?

    /** Where to open a pack: the last article read, if it still exists, else the main page. */
    suspend fun startPath(packId: String): String?

    fun isBookmarked(packId: String, path: String): Flow<Boolean>

    suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean)

    /** Stores how far down [path] the reader is, 0..1. */
    suspend fun savePosition(packId: String, path: String, scrollFraction: Float)

    suspend fun position(packId: String, path: String): Float?

    /**
     * Deletes the pack's file, its entry, bookmarks and reading positions. False only when the file
     * exists and can't be deleted; an unknown id is already gone, so it returns true.
     */
    suspend fun remove(packId: String): Boolean

    /**
     * [newPackId] takes [oldPackId]'s place (another flavour of the same pack, Plan KB walkthrough): the old file and
     * entry go as [remove] takes them, but its bookmarks, reading places, tabs and history move to the new pack, as a
     * new snapshot inherits them. False, with nothing changed, when the new pack isn't readable, the two aren't the same
     * ZIM name, or the old file can't be deleted.
     *
     * With [acrossNames] the two may be different ZIMs of one set (Plan WF Decision 5: Top 1 million takes Best 50,000's
     * place). Article paths match across a set's slices; a bookmark or reading place whose article the new pack lacks
     * is kept as a history row instead.
     */
    suspend fun supersede(oldPackId: String, newPackId: String, acrossNames: Boolean = false): Boolean = false
}
