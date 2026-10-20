package app.granger.core.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a track or audiobook comes from; part of its id (`track:phone:<fingerprint>`). */
enum class MediaOrigin { PHONE, LIBRIVOX, ARCHIVE_ORG }

/**
 * A playable audio file: a song, or one file of an audiobook ([audiobookId] set). Multi-valued tags
 * ([albumArtists], [artists], [composers], [genres]) are `"; "`-joined. [status] is OK or MISSING; a MISSING row
 * keeps its playlist entries and positions for when the file returns.
 */
@Entity(tableName = "tracks", indices = [Index("albumKey"), Index("audiobookId")])
data class Track(
    @PrimaryKey val id: String,
    val origin: MediaOrigin,
    val uri: String,
    val folder: String,
    val title: String,
    val album: String,
    val albumKey: String,
    val albumArtists: String,
    val artists: String,
    val composers: String,
    val genres: String,
    val disc: Int?,
    val trackNo: Int?,
    val date: String,
    val originalDate: String,
    val releaseType: String,
    val sortTitle: String,
    val sortAlbum: String,
    val sortArtist: String,
    val sortAlbumArtist: String,
    val durationMs: Long,
    val rgTrackGain: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGain: Float?,
    val rgAlbumPeak: Float?,
    val coverPath: String?,
    val audiobookId: String?,
    val status: PackStatus,
    val addedAt: Long,
)

/** An audiobook: one or more [Track]s, in [AudiobookChapter] order. */
@Entity(tableName = "audiobooks")
data class Audiobook(
    @PrimaryKey val id: String,
    val origin: MediaOrigin,
    val sourceRef: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val coverPath: String?,
    val durationMs: Long,
    val status: PackStatus,
    val addedAt: Long,
)

/** A chapter of an audiobook, [startMs] into [trackId]. Derived from the files, so a re-scan may replace them. */
@Entity(tableName = "audiobook_chapters", primaryKeys = ["audiobookId", "ordinal"])
data class AudiobookChapter(
    val audiobookId: String,
    val ordinal: Int,
    val title: String,
    val trackId: String,
    val startMs: Long,
    val durationMs: Long,
)

/** Where the listener is in an audiobook, and its own playback settings. Never deleted by a scan. */
@Entity(tableName = "audiobook_states")
data class AudiobookState(
    @PrimaryKey val audiobookId: String,
    val trackId: String,
    val positionMs: Long,
    val speed: Float,
    val skipSilence: Boolean,
    val volumeBoostMb: Int,
    val finished: Boolean,
    val updatedAt: Long,
)

/** A bookmark in an audiobook. Distinct from web-page [Bookmark]s and e-book [BookAnnotation]s. */
@Entity(tableName = "audio_bookmarks", indices = [Index("audiobookId")])
data class AudioBookmark(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val audiobookId: String,
    val trackId: String,
    val positionMs: Long,
    val title: String,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "playlists")
data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long, val name: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "ordinal"])
data class PlaylistEntry(val playlistId: Long, val ordinal: Int, val trackId: String)

/** Single row (id = 0). [queue] and [shuffledOrder] are "\n"-joined track ids / indices. repeatMode: 0 off, 1 all, 2 one. */
@Entity(tableName = "music_state")
data class MusicState(
    @PrimaryKey val id: Int = 0,
    val queue: String,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val shuffledOrder: String,
    val repeatMode: Int,
    val sourceKey: String,
    val updatedAt: Long,
)

/** [key]: "album:<albumKey>", "audiobook:<id>", "playlist:<id>", "artist:<name>", "genre:<name>", "folder:<path>", "songs". */
@Entity(tableName = "recent_plays", indices = [Index("playedAt")])
data class RecentPlay(@PrimaryKey val key: String, val title: String, val playedAt: Long)

/** A folder the user picked to scan; [audiobooks] files its contents as audiobooks rather than music. */
@Entity(tableName = "media_folders")
data class MediaFolder(@PrimaryKey val uri: String, val audiobooks: Boolean, val addedAt: Long)

/** Scanner cache: skip re-fingerprinting and re-tagging an unchanged file. */
@Entity(tableName = "scan_cache")
data class ScanCacheEntry(@PrimaryKey val uri: String, val sizeBytes: Long, val modifiedMs: Long, val fingerprint: String)

/**
 * What a downloaded album's manifest says about it (an archive.org item's description), which [Track] has no column
 * for. Written by the downloaded-media sync; deleted when the album's last track row is forgotten. Phone albums have none.
 */
@Entity(tableName = "album_notes")
data class AlbumNote(@PrimaryKey val albumKey: String, val description: String)
