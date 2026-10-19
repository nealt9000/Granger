package app.granger.core.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * The user's music playlists: the app-level function surface later projects call (spec §10). Entries refer to track
 * ids, so a playlist survives its tracks going MISSING and coming back. An entry's ordinal is its position in the
 * playlist; ordinals are always dense (0..n-1).
 */
interface Playlists {
    /** Every playlist, by name. */
    val all: Flow<List<Playlist>>

    /** The playlist's tracks in order, MISSING ones included; entries whose track row doesn't exist are skipped. */
    fun tracks(playlistId: Long): Flow<List<Track>>

    /** Returns the new playlist's id. */
    suspend fun create(name: String, trackIds: List<String> = emptyList()): Long
    suspend fun rename(id: Long, name: String); suspend fun delete(id: Long)

    /** [add] appends; [move] and [remove] take entry ordinals and do nothing when one is out of range. */
    suspend fun add(id: Long, trackIds: List<String>); suspend fun move(id: Long, from: Int, to: Int); suspend fun remove(id: Long, ordinal: Int)
}

/**
 * `playlist_entries` is keyed by (playlistId, ordinal), so a reorder can't renumber rows in place without colliding:
 * [move] and [remove] rewrite the playlist's entries (delete, then insert renumbered) inside one transaction.
 */
class RoomPlaylists(private val db: LibraryDatabase, private val now: () -> Long = System::currentTimeMillis) : Playlists {
    private val dao = db.playbackDao()

    override val all: Flow<List<Playlist>> = dao.observePlaylists()

    override fun tracks(playlistId: Long): Flow<List<Track>> = dao.observePlaylistTracks(playlistId)

    override suspend fun create(name: String, trackIds: List<String>): Long = db.withTransaction {
        val at = now()
        val id = dao.insertPlaylist(Playlist(0, name.trim(), createdAt = at, updatedAt = at))
        dao.insertEntries(trackIds.mapIndexed { i, trackId -> PlaylistEntry(id, i, trackId) })
        id
    }

    override suspend fun rename(id: Long, name: String) {
        dao.renamePlaylist(id, name.trim(), now())
    }

    override suspend fun delete(id: Long) {
        db.withTransaction {
            dao.deleteEntries(id)
            dao.deletePlaylist(id)
        }
    }

    override suspend fun add(id: Long, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        db.withTransaction {
            if (dao.playlist(id) == null) return@withTransaction
            val next = dao.entries(id).size
            dao.insertEntries(trackIds.mapIndexed { i, trackId -> PlaylistEntry(id, next + i, trackId) })
            dao.touchPlaylist(id, now())
        }
    }

    override suspend fun move(id: Long, from: Int, to: Int) {
        rewrite(id) { ids ->
            if (from !in ids.indices || to !in ids.indices || from == to) null
            else ids.toMutableList().apply { add(to, removeAt(from)) }
        }
    }

    override suspend fun remove(id: Long, ordinal: Int) {
        rewrite(id) { ids -> if (ordinal !in ids.indices) null else ids.toMutableList().apply { removeAt(ordinal) } }
    }

    /** Replaces the playlist's entries with [change] of its track ids, renumbered 0..n-1; null from [change] leaves it alone. */
    private suspend fun rewrite(id: Long, change: (List<String>) -> List<String>?) {
        db.withTransaction {
            val ids = dao.entries(id).map { it.trackId }
            val changed = change(ids) ?: return@withTransaction
            dao.deleteEntries(id)
            dao.insertEntries(changed.mapIndexed { i, trackId -> PlaylistEntry(id, i, trackId) })
            dao.touchPlaylist(id, now())
        }
    }
}
