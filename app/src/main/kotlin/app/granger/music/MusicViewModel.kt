package app.granger.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PackStatus
import app.granger.core.library.Playlist
import app.granger.core.library.PlaylistItem
import app.granger.core.library.Playlists
import app.granger.core.library.Shelf
import app.granger.core.library.Shelves
import app.granger.core.library.Track
import app.granger.core.player.PlaySource
import app.granger.core.player.PlayerHub
import app.granger.core.downloads.DownloadRecord
import app.granger.media.MediaDownloads
import app.granger.sources.ItemStatus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Music media type's id (seeded). */
const val MUSIC_TYPE_ID = "music"

/** The name of the unscoped Music view (exact, per the plan). */
const val ALL_MUSIC = "All music"

const val UNKNOWN_GENRE = "Unknown genre"

/** How the Songs tab is ordered. */
enum class SongSort(val label: String) { TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), YEAR("Year"), DATE_ADDED("Date added") }

/** How an artist page groups albums, in display order (Auxio). Untyped albums are [ALBUMS]. */
enum class ReleaseGroup(val label: String) {
    ALBUMS("Albums"), EPS("EPs"), SINGLES("Singles"), COMPILATIONS("Compilations"), LIVE("Live"),
    SOUNDTRACKS("Soundtracks"), OTHERS("Other releases"),
}

data class ArtistSummary(val name: String, val albumCount: Int, val songCount: Int)
data class GenreSummary(val name: String, val songCount: Int)

/** [path] is the track's stored folder (e.g. "Music/Rock/"); [name] is it without the trailing slash. */
data class FolderSummary(val path: String, val name: String, val songCount: Int)

data class AlbumSection(val group: ReleaseGroup, val albums: List<AlbumSummary>)

/**
 * A downloaded album (archive.org) has its [download] state, its files in ([filesDone]) of [filesTotal], and
 * [onRemoveDownload]; a phone album has neither (null).
 */
data class AlbumPage(
    val album: AlbumSummary,
    val tracks: List<Track>,
    val download: ItemStatus? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val onRemoveDownload: (() -> Unit)? = null,
)

/** [sections]: albums they're an album artist of, by release type; [appearsOn]: albums they're only a track artist on. */
data class ArtistPage(val name: String, val sections: List<AlbumSection>, val appearsOn: List<AlbumSummary>, val songs: List<Track>)
data class GenrePage(val name: String, val albums: List<AlbumSummary>, val songs: List<Track>)
data class FolderPage(val path: String, val name: String, val songs: List<Track>)
/** [items] carry their entry ordinals, which [MusicViewModel.moveInPlaylist] and [MusicViewModel.removeFromPlaylist] take. */
data class PlaylistPage(val playlist: Playlist, val items: List<PlaylistItem>) {
    val tracks: List<Track> get() = items.map { it.track }
}

data class MusicUi(
    val title: String,
    val sort: SongSort,
    val songs: List<Track>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
    val genres: List<GenreSummary>,
    val folders: List<FolderSummary>,
    /** Always every playlist: playlists aren't scoped to a collection. */
    val playlists: List<Playlist>,
)

/** `RecentPlay` keys and titles for what a play started from. */
object MusicSources {
    const val ALBUM = "album:"
    const val PLAYLIST = "playlist:"
    const val ARTIST = "artist:"
    const val GENRE = "genre:"
    const val FOLDER = "folder:"
    const val SONGS = "songs"

    fun album(a: AlbumSummary) = PlaySource("$ALBUM${a.key}", a.title)
    fun playlist(p: Playlist) = PlaySource("$PLAYLIST${p.id}", p.name)
    fun artist(name: String) = PlaySource("$ARTIST$name", name)
    fun genre(name: String) = PlaySource("$GENRE$name", name)
    fun folder(f: FolderPage) = PlaySource("$FOLDER${f.path}", f.name)
    fun songs(title: String) = PlaySource(SONGS, title)

    /**
     * What a `RecentPlay` row is called: its stored title, except an untagged album's, which follows [Albums.title]
     * (rows saved before untagged albums were named after their folder say "Unknown album").
     */
    fun title(key: String, stored: String): String =
        key.takeIf { it.startsWith(ALBUM) }?.let { Albums.untaggedTitle(it.removePrefix(ALBUM)) } ?: stored

    /** A playlist key's id; null for any other key. */
    fun playlistId(key: String): Long? = key.takeIf { it.startsWith(PLAYLIST) }?.removePrefix(PLAYLIST)?.toLongOrNull()
}

/** Pure browsing logic over music tracks (any status; [MusicViewModel] browses present ones, playlists any). */
object MusicCatalog {
    private const val JOIN = "; "

    private fun split(joined: String): List<String> = joined.split(JOIN).map { it.trim() }.filter { it.isNotEmpty() }

    /** Artists are matched without regard to case or surrounding space. */
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** A track's album artists; with none tagged, its track artists stand in (Auxio). */
    fun albumArtistsOf(t: Track): List<String> =
        split(t.albumArtists).ifEmpty { split(t.artists) }.ifEmpty { listOf(Albums.UNKNOWN_ARTIST) }

    /** A track's own artists; with none tagged, its album artists stand in. */
    fun trackArtistsOf(t: Track): List<String> =
        split(t.artists).ifEmpty { split(t.albumArtists) }.ifEmpty { listOf(Albums.UNKNOWN_ARTIST) }

    fun genresOf(t: Track): List<String> = split(t.genres).ifEmpty { listOf(UNKNOWN_GENRE) }

    private fun sortName(sort: String, name: String) = sort.ifBlank { name }.lowercase(Locale.ROOT)

    /** Albums as [Albums.summaries] builds them, with the track artists named where no album artist is tagged. */
    fun albums(tracks: List<Track>): List<AlbumSummary> {
        val byKey = tracks.groupBy { it.albumKey }
        return Albums.summaries(tracks).map { a ->
            if (a.albumArtist != Albums.UNKNOWN_ARTIST) a
            else a.copy(albumArtist = byKey[a.key].orEmpty().flatMap(::albumArtistsOf).distinctBy(::nameKey).joinToString(JOIN))
        }
    }

    fun sortSongs(tracks: List<Track>, sort: SongSort): List<Track> {
        val byTitle = compareBy<Track>({ sortName(it.sortTitle, it.title) }, { it.id })
        val inAlbum = compareBy<Track>({ it.disc ?: 1 }, { it.trackNo ?: Int.MAX_VALUE }).then(byTitle)
        val comparator = when (sort) {
            SongSort.TITLE -> byTitle
            SongSort.ARTIST -> compareBy<Track>({ sortName(it.sortArtist, trackArtistsOf(it).joinToString(JOIN)) },
                { sortName(it.sortAlbum, Albums.title(it)) }, { it.albumKey }).then(inAlbum)
            SongSort.ALBUM -> compareBy<Track>({ sortName(it.sortAlbum, Albums.title(it)) }, { it.albumKey }).then(inAlbum)
            SongSort.YEAR -> compareBy<Track>({ it.date.trim().ifEmpty { "￿" } }, { sortName(it.sortAlbum, Albums.title(it)) },
                { it.albumKey }).then(inAlbum)
            SongSort.DATE_ADDED -> compareByDescending<Track> { it.addedAt }.then(byTitle)
        }
        return tracks.sortedWith(comparator)
    }

    /** Every album artist and track artist, merged by name. */
    fun artists(tracks: List<Track>): List<ArtistSummary> {
        class Acc(val name: String) { val albums = HashSet<String>(); var songs = 0; var sort: String? = null }
        val all = LinkedHashMap<String, Acc>()
        fun acc(name: String) = all.getOrPut(nameKey(name)) { Acc(name) }
        for (t in tracks) {
            val albumArtists = albumArtistsOf(t)
            albumArtists.forEach { acc(it).albums += t.albumKey }
            if (albumArtists.size == 1 && t.sortAlbumArtist.isNotBlank()) acc(albumArtists[0]).apply { sort = sort ?: t.sortAlbumArtist }
            val trackArtists = trackArtistsOf(t)
            trackArtists.forEach { acc(it).songs++ }
            if (trackArtists.size == 1 && t.sortArtist.isNotBlank()) acc(trackArtists[0]).apply { sort = sort ?: t.sortArtist }
        }
        return all.values.sortedWith(compareBy({ sortName(it.sort.orEmpty(), it.name) }, { it.name }))
            .map { ArtistSummary(it.name, it.albums.size, it.songs) }
    }

    fun releaseGroup(releaseType: String): ReleaseGroup {
        val tokens = releaseType.lowercase(Locale.ROOT).split(';', '/', ',', '+').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return when {
            tokens.isEmpty() -> ReleaseGroup.ALBUMS
            "compilation" in tokens -> ReleaseGroup.COMPILATIONS
            "soundtrack" in tokens -> ReleaseGroup.SOUNDTRACKS
            "live" in tokens -> ReleaseGroup.LIVE
            tokens.any { it in OTHER_SECONDARY } -> ReleaseGroup.OTHERS
            "ep" in tokens -> ReleaseGroup.EPS
            "single" in tokens -> ReleaseGroup.SINGLES
            "album" in tokens -> ReleaseGroup.ALBUMS
            else -> ReleaseGroup.OTHERS
        }
    }

    private val OTHER_SECONDARY = setOf("remix", "mixtape", "mixtape/street", "dj-mix", "demo", "spokenword", "interview", "audiobook", "audio drama", "broadcast", "other")

    /** Null when no track names [name] as an album or track artist. */
    fun artistPage(name: String, tracks: List<Track>): ArtistPage? {
        val key = nameKey(name)
        val shown = tracks.asSequence().flatMap { albumArtistsOf(it) + trackArtistsOf(it) }.firstOrNull { nameKey(it) == key } ?: return null
        val byAlbum = tracks.groupBy { it.albumKey }
        val own = ArrayList<AlbumSummary>()
        val appears = ArrayList<AlbumSummary>()
        val albums = albums(tracks)
        for (a in albums) {
            val group = byAlbum[a.key].orEmpty()
            when {
                group.any { t -> albumArtistsOf(t).any { nameKey(it) == key } } -> own += a
                group.any { t -> trackArtistsOf(t).any { nameKey(it) == key } } -> appears += a
            }
        }
        val sections = own.groupBy { releaseGroup(it.releaseType) }.toSortedMap().map { (g, list) -> AlbumSection(g, list) }
        val order = albums.withIndex().associate { it.value.key to it.index }
        val songs = tracks.filter { t -> trackArtistsOf(t).any { nameKey(it) == key } }
            .groupBy { it.albumKey }.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
            .flatMap { Albums.ordered(it.value) }
        return ArtistPage(shown, sections, appears, songs)
    }

    fun genres(tracks: List<Track>): List<GenreSummary> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in tracks) for (g in genresOf(t)) {
            val k = nameKey(g)
            counts[k] = (counts[k]?.first ?: g) to ((counts[k]?.second ?: 0) + 1)
        }
        return counts.values.sortedBy { it.first.lowercase(Locale.ROOT) }.map { GenreSummary(it.first, it.second) }
    }

    fun genrePage(name: String, tracks: List<Track>): GenrePage {
        val key = nameKey(name)
        val inGenre = tracks.filter { t -> genresOf(t).any { nameKey(it) == key } }
        return GenrePage(name, albums(inGenre), sortSongs(inGenre, SongSort.TITLE))
    }

    fun folderName(path: String) = path.trimEnd('/').ifEmpty { path }

    fun folders(tracks: List<Track>): List<FolderSummary> =
        tracks.groupBy { it.folder }.map { (path, group) -> FolderSummary(path, folderName(path), group.size) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun folderPage(path: String, tracks: List<Track>): FolderPage {
        val inFolder = tracks.filter { it.folder == path }
        return FolderPage(path, folderName(path), sortSongs(inFolder, SongSort.ALBUM))
    }

    /** [key]'s album in album order, as the album page lists it; empty when no track has that album key. */
    fun albumTracks(key: String, tracks: List<Track>): List<Track> = Albums.ordered(tracks.filter { it.albumKey == key })

    /**
     * The list a music `RecentPlay` key ([MusicSources]) played, rebuilt from [tracks] as its screen builds it; null
     * when the key isn't a music list or its source is gone. "songs" is all music by title (the Songs tab's default
     * order; the key doesn't record a collection or a sort). Playlists need their entries: see [MusicSources.playlistId].
     */
    fun sourceTracks(key: String, tracks: List<Track>): List<Track>? = when {
        key == MusicSources.SONGS -> sortSongs(tracks, SongSort.TITLE)
        key.startsWith(MusicSources.ALBUM) -> albumTracks(key.removePrefix(MusicSources.ALBUM), tracks)
        key.startsWith(MusicSources.ARTIST) -> artistPage(key.removePrefix(MusicSources.ARTIST), tracks)?.songs
        key.startsWith(MusicSources.GENRE) -> genrePage(key.removePrefix(MusicSources.GENRE), tracks).songs
        key.startsWith(MusicSources.FOLDER) -> folderPage(key.removePrefix(MusicSources.FOLDER), tracks).songs
        else -> null
    }?.takeIf { it.isNotEmpty() }

    /**
     * What to hand the player: only playable (OK) tracks, with the tapped track's index among them. Null when nothing
     * can play, or the tapped track itself is MISSING. [tapped] null starts at the first playable track.
     */
    fun playable(tracks: List<Track>, tapped: Int?): Pair<List<String>, Int>? {
        if (tapped != null && tracks.getOrNull(tapped)?.status != PackStatus.OK) return null
        val ids = tracks.filter { it.status == PackStatus.OK }.map { it.id }
        if (ids.isEmpty()) return null
        val start = if (tapped == null) 0 else tracks.take(tapped).count { it.status == PackStatus.OK }
        return ids to start
    }
}

/**
 * Music browsing: the Auxio-style tabs for [collectionId]'s albums, or for all music when it's null (the detail
 * pages are built with null: their routes aren't scoped). A collection's songs, albums, artists, genres and folders
 * are those of the albums placed in it ([Shelves.collection]'s `albums`); playlists are global.
 *
 * Browsing shows present tracks only ([Albums.present]): a MISSING track is in no tab or page, and an album, artist,
 * genre or folder with nothing else goes with it. Playlists keep their MISSING songs, dimmed, so the user sees what's
 * gone; nothing is deleted, and a track that returns is back everywhere.
 */
class MusicViewModel(
    private val collectionId: String?,
    media: MediaLibrary,
    private val shelves: Shelves,
    private val playlists: Playlists,
    private val hub: PlayerHub,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** The downloads ledger: a downloaded album's progress. */
    downloads: Flow<List<DownloadRecord>> = flowOf(emptyList()),
    /** Removes a downloaded album's files by its group (`MediaDownloader.remove`, run in the app scope). */
    private val remover: (groupId: String) -> Unit = {},
) : ViewModel() {
    /** Groups' file counts from their manifests, read once (on [compute]). */
    private val fileCounts = ConcurrentHashMap<String, Int>()

    private val mediaRecords: Flow<List<DownloadRecord>> =
        downloads.map { all -> all.filter { it.groupId?.startsWith(MediaDownloads.PREFIX) == true } }.distinctUntilChanged()

    private val sort = MutableStateFlow(SongSort.TITLE)

    private data class Scope(val title: String, val tracks: List<Track>)

    private val present: Flow<List<Track>> = media.tracks.map(Albums::present)

    private val scope: Flow<Scope> =
        if (collectionId == null) {
            present.map { Scope(ALL_MUSIC, it) }
        } else {
            combine(present, shelves.collection(collectionId)) { tracks, row ->
                val keys = row?.albums.orEmpty().mapTo(HashSet()) { it.key }
                Scope(row?.collection?.name.orEmpty(), tracks.filter { it.albumKey in keys })
            }
        }.distinctUntilChanged()

    private val tracks: Flow<List<Track>> = scope.map { it.tracks }.distinctUntilChanged()

    /** Null until loaded. */
    val ui: StateFlow<MusicUi?> = combine(scope, playlists.all, sort) { s, lists, order ->
        MusicUi(
            title = s.title,
            sort = order,
            songs = MusicCatalog.sortSongs(s.tracks, order),
            albums = MusicCatalog.albums(s.tracks),
            artists = MusicCatalog.artists(s.tracks),
            genres = MusicCatalog.genres(s.tracks),
            folders = MusicCatalog.folders(s.tracks),
            playlists = lists,
        )
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Music shelf only: an album moves between Music collections. */
    val moveTargets: StateFlow<List<Shelf>> = shelves.shelves
        .map { all -> all.filter { it.mediaType.id == MUSIC_TYPE_ID } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every playlist, for "Add to playlist". */
    val allPlaylists: StateFlow<List<Playlist>> =
        playlists.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSort(order: SongSort) { sort.value = order }

    fun album(key: String): Flow<AlbumPage?> = combine(tracks, mediaRecords) { all, records ->
        val tracksOf = MusicCatalog.albumTracks(key, all)
        val album = MusicCatalog.albums(tracksOf).firstOrNull() ?: return@combine null
        val group = MediaDownloads.groupOfAlbum(album.key)
        val download = group?.let { g ->
            val count = fileCounts[g] ?: MediaDownloads.manifestFileCount(g, records)?.also { fileCounts[g] = it }
            MediaDownloads.progress(g, records, count)
        }
        AlbumPage(
            album, tracksOf,
            download = download?.status,
            filesDone = download?.done ?: 0,
            filesTotal = download?.total ?: 0,
            onRemoveDownload = group?.let { g -> { fileCounts.remove(g); remover(g) } },
        )
    }.flowOn(compute)

    fun artist(name: String): Flow<ArtistPage?> = tracks.map { MusicCatalog.artistPage(name, it) }.flowOn(compute)
    fun genre(name: String): Flow<GenrePage> = tracks.map { MusicCatalog.genrePage(name, it) }.flowOn(compute)
    fun folder(path: String): Flow<FolderPage> = tracks.map { MusicCatalog.folderPage(path, it) }.flowOn(compute)

    /** Null once the playlist is deleted. */
    fun playlist(id: Long): Flow<PlaylistPage?> = combine(playlists.all, playlists.items(id)) { lists, items ->
        lists.firstOrNull { it.id == id }?.let { PlaylistPage(it, items) }
    }

    /** Plays [tracks] from [tapped] (null: the first playable one), leaving MISSING tracks out of the queue. */
    fun play(tracks: List<Track>, tapped: Int?, source: PlaySource, shuffle: Boolean = false) {
        val (ids, start) = MusicCatalog.playable(tracks, tapped) ?: return
        hub.playTracks(ids, start, shuffle, source)
    }

    fun playNext(tracks: List<Track>) {
        playableIds(tracks).takeIf { it.isNotEmpty() }?.let(hub::playNext)
    }

    fun addToQueue(tracks: List<Track>) {
        playableIds(tracks).takeIf { it.isNotEmpty() }?.let(hub::addToQueue)
    }

    private fun playableIds(tracks: List<Track>) = tracks.filter { it.status == PackStatus.OK }.map { it.id }

    /** [album]'s tracks in album order, from the current catalog. */
    private suspend fun albumTracks(album: AlbumSummary): List<Track> = MusicCatalog.albumTracks(album.key, tracks.first())

    fun playAlbum(album: AlbumSummary, shuffle: Boolean = false) {
        viewModelScope.launch { play(albumTracks(album), null, MusicSources.album(album), shuffle) }
    }

    fun playNextAlbum(album: AlbumSummary) { viewModelScope.launch { playNext(albumTracks(album)) } }
    fun queueAlbum(album: AlbumSummary) { viewModelScope.launch { addToQueue(albumTracks(album)) } }

    fun addAlbumToPlaylist(playlistId: Long, album: AlbumSummary) {
        viewModelScope.launch { playlists.add(playlistId, albumTracks(album).map { it.id }) }
    }

    fun createPlaylistFromAlbum(name: String, album: AlbumSummary) {
        if (name.isBlank()) return
        viewModelScope.launch { playlists.create(name.trim(), albumTracks(album).map { it.id }) }
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()) {
        if (name.isBlank()) return
        viewModelScope.launch { playlists.create(name.trim(), tracks.map { it.id }) }
    }

    fun addToPlaylist(playlistId: Long, tracks: List<Track>) {
        viewModelScope.launch { playlists.add(playlistId, tracks.map { it.id }) }
    }

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { playlists.rename(id, name.trim()) }
    }

    fun deletePlaylist(id: Long) { viewModelScope.launch { playlists.delete(id) } }
    /** [from], [to] and [ordinal] are entry ordinals ([PlaylistItem.ordinal]), not row positions. */
    fun moveInPlaylist(id: Long, from: Int, to: Int) { viewModelScope.launch { playlists.move(id, from, to) } }
    fun removeFromPlaylist(id: Long, ordinal: Int) { viewModelScope.launch { playlists.remove(id, ordinal) } }

    /** The Music collection [album] is placed in, if the shelves have loaded. */
    fun collectionOf(album: AlbumSummary): String? =
        moveTargets.value.flatMap { it.collections }.firstOrNull { row -> row.albums.any { it.key == album.key } }?.collection?.id

    fun moveAlbum(album: AlbumSummary, toCollectionId: String) {
        viewModelScope.launch { shelves.moveAlbum(album, toCollectionId) }
    }

    fun moveAlbumToNewCollection(album: AlbumSummary, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { shelves.moveAlbum(album, shelves.createCollection(name.trim(), MUSIC_TYPE_ID)) }
    }
}
