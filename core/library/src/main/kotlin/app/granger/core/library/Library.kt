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
}
