package app.granger.music

import app.granger.core.library.Albums
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.Playlist
import app.granger.core.library.Playlists
import app.granger.core.library.ScanResult
import app.granger.core.library.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Serves [trackList] as the music catalog; sync is not supported. */
class FakeMediaLibrary(tracks: List<Track> = emptyList()) : MediaLibrary {
    val trackList = MutableStateFlow(tracks)
    override val tracks: Flow<List<Track>> = trackList
    override val audiobooks: Flow<List<Audiobook>> = flowOf(emptyList())
    override fun chapters(audiobookId: String): Flow<List<AudiobookChapter>> = flowOf(emptyList())
    override suspend fun track(id: String): Track? = trackList.value.firstOrNull { it.id == id }
    override suspend fun audiobook(id: String): Audiobook? = null
    override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) = error("not in tests")
    override suspend fun markAllMissing(origin: MediaOrigin) = error("not in tests")
}

/** In-memory [Playlists] over [media]'s tracks. */
class FakePlaylists(private val media: FakeMediaLibrary) : Playlists {
    val lists = MutableStateFlow<List<Playlist>>(emptyList())
    val entries = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    private var nextId = 1L

    override val all: Flow<List<Playlist>> = lists.map { all -> all.sortedBy { it.name.lowercase() } }
    override fun tracks(playlistId: Long): Flow<List<Track>> = combine(entries, media.trackList) { e, tracks ->
        e[playlistId].orEmpty().mapNotNull { id -> tracks.firstOrNull { it.id == id } }
    }

    override suspend fun create(name: String, trackIds: List<String>): Long {
        val id = nextId++
        lists.update { it + Playlist(id, name.trim(), 0L, 0L) }
        entries.update { it + (id to trackIds) }
        return id
    }

    override suspend fun rename(id: Long, name: String) { lists.update { all -> all.map { if (it.id == id) it.copy(name = name.trim()) else it } } }
    override suspend fun delete(id: Long) { lists.update { all -> all.filterNot { it.id == id } }; entries.update { it - id } }
    override suspend fun add(id: Long, trackIds: List<String>) { entries.update { it + (id to it[id].orEmpty() + trackIds) } }
    override suspend fun move(id: Long, from: Int, to: Int) {
        entries.update { it + (id to it[id].orEmpty().toMutableList().apply { add(to, removeAt(from)) }) }
    }
    override suspend fun remove(id: Long, ordinal: Int) {
        entries.update { it + (id to it[id].orEmpty().toMutableList().apply { removeAt(ordinal) }) }
    }
}

/** A phone song; its album key is computed as the scanner does. */
fun song(
    ref: String,
    title: String = ref,
    album: String = "Album",
    albumArtists: String = "Artist",
    artists: String = albumArtists,
    genres: String = "",
    folder: String = "Music/",
    trackNo: Int? = null,
    date: String = "",
    releaseType: String = "",
    addedAt: Long = 0L,
    status: PackStatus = PackStatus.OK,
) = Track(
    id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = "content://media/$ref", folder = folder,
    title = title, album = album, albumKey = Albums.key(albumArtists, album, folder),
    albumArtists = albumArtists, artists = artists, composers = "", genres = genres, disc = null, trackNo = trackNo,
    date = date, originalDate = "", releaseType = releaseType, sortTitle = "", sortAlbum = "", sortArtist = "",
    sortAlbumArtist = "", durationMs = 180_000L, rgTrackGain = null, rgTrackPeak = null, rgAlbumGain = null,
    rgAlbumPeak = null, coverPath = null, audiobookId = null, status = status, addedAt = addedAt,
)
