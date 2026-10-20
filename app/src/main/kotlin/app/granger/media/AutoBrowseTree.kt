package app.granger.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.granger.audiobooks.AudiobookShelf
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaNames
import app.granger.core.library.MediaText
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Playlist
import app.granger.core.library.Playlists
import app.granger.core.library.RecentPlay
import app.granger.core.library.Track
import app.granger.core.player.MediaBrowseTree
import app.granger.core.player.PlayerHub
import app.granger.music.MusicCatalog
import app.granger.music.MusicSources
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What Android Auto browses (spec §6/§7): Recent, Music (Albums, Artists, Playlists) and Audiobooks, over both players.
 *
 * Ids are stable strings: the folders `recent`, `music`, `music/albums`, `music/artists`, `music/playlists`,
 * `audiobooks`; `album:<albumKey>` (its songs; plays from the first), `album:<albumKey>#<trackId>` (plays the album from
 * that song), `artist:<name>` (its albums; plays its songs), `playlist:<id>` and `playlist:<id>#<ordinal>`, an
 * audiobook's own id (`audiobook:…`, resumes it) and `recent:<recent key>` (resumes it through [PlayResumer]).
 *
 * MISSING items are left out; an empty list shows one "Nothing here yet" row. Items carry no URI: Auto plays by id
 * through [play], which goes through the [hub], so the engine stays the only thing that loads the player. Covers are
 * [artwork]'s content:// URIs (none outside its roots). Lists are built on [compute]; hub calls run on the caller's
 * thread (the session's main thread).
 */
class AutoBrowseTree(
    private val media: MediaLibrary,
    private val playlists: Playlists,
    private val playback: PlaybackDao,
    private val index: MediaIndex,
    private val resumer: PlayResumer,
    private val songs: SongPlayer,
    private val hub: PlayerHub,
    private val artwork: Artwork,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : MediaBrowseTree {

    override fun root(): MediaItem = folder(ROOT, "Granger", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    override suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? {
        val all = list(parentId) ?: return null
        if (all.isEmpty()) return if (page <= 0) listOf(nothing()) else emptyList()
        return if (pageSize < 1) all else all.drop(page.coerceAtLeast(0) * pageSize).take(pageSize)
    }

    /** Every child of [parentId] as Auto lists it; null for an unknown (or wholly MISSING) parent. */
    private suspend fun list(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(recentFolder(), musicFolder(), audiobooksFolder())
        parentId == MUSIC -> listOf(albumsFolder(), artistsFolder(), playlistsFolder())
        parentId == RECENT -> recents().map { (play, item) -> item.takeIf { play != null } }.filterNotNull()
        parentId == ALBUMS -> withContext(compute) {
            albums(present()).sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.key })).map(::albumItem)
        }
        parentId == ARTISTS -> withContext(compute) { MusicCatalog.artists(present()).map { artistItem(it.name) } }
        parentId == PLAYLISTS -> playlists.all.first().map(::playlistItem)
        parentId == AUDIOBOOKS -> shelf().map(::bookItem)
        parentId.startsWith(ALBUM) && TRACK !in parentId -> withContext(compute) {
            MusicCatalog.albumTracks(parentId.removePrefix(ALBUM), present()).map(::songItem).ifEmpty { null }
        }
        parentId.startsWith(ARTIST) -> withContext(compute) {
            MusicCatalog.artistPage(parentId.removePrefix(ARTIST), present())?.let { page ->
                (page.sections.flatMap { it.albums } + page.appearsOn).map(::albumItem)
            }
        }
        parentId.startsWith(PLAYLIST) && ENTRY !in parentId -> playlistId(parentId)?.let { id ->
            if (playlists.all.first().none { it.id == id }) null
            else playlists.items(id).first().filter { it.track.status != PackStatus.MISSING }
                .map { entryItem(id, it.ordinal, it.track) }
        }
        else -> null
    }

    override suspend fun item(id: String): MediaItem? = when (id) {
        ROOT -> root()
        RECENT -> recentFolder()
        MUSIC -> musicFolder()
        AUDIOBOOKS -> audiobooksFolder()
        ALBUMS -> albumsFolder()
        ARTISTS -> artistsFolder()
        PLAYLISTS -> playlistsFolder()
        else -> when {
            id.startsWith(RECENT_ITEM) -> recents().firstOrNull { it.first?.key == id.removePrefix(RECENT_ITEM) }?.second
            id.startsWith(ALBUM) -> song(id)?.let(::songItem) ?: withContext(compute) {
                albums(present()).firstOrNull { "$ALBUM${it.key}" == id }?.let(::albumItem)
            }
            id.startsWith(ARTIST) -> withContext(compute) {
                MusicCatalog.artistPage(id.removePrefix(ARTIST), present())?.let { artistItem(it.name) }
            }
            id.startsWith(PLAYLIST) -> playlistItemOf(id)
            id.startsWith(AUDIOBOOK) -> media.audiobook(id)?.takeIf { it.status == PackStatus.OK }?.let(::bookItem)
            else -> null
        }
    }

    override suspend fun search(query: String): List<MediaItem> {
        if (MediaText.words(query).isEmpty()) return emptyList()
        val hits = index.search(query)
        return hits.artists.map { artistItem(it.name) } + hits.albums.map(::albumItem) +
            hits.songs.map(::songItem) + hits.audiobooks.map(::bookItem)
    }

    override suspend fun play(id: String, searchQuery: String?): Boolean = when {
        id.isEmpty() -> playBest(searchQuery.orEmpty())
        id.startsWith(RECENT_ITEM) -> {
            val key = id.removePrefix(RECENT_ITEM)
            val title = playback.observeRecentPlays(RECENT_LIMIT).first().firstOrNull { it.key == key }?.title ?: key
            resumer.resume(key, title)
        }
        id.startsWith(ALBUM) && TRACK in id -> song(id)?.let { songs.play(it); true } ?: false
        id.startsWith(ALBUM) -> playAlbum(id.removePrefix(ALBUM))
        id.startsWith(ARTIST) -> playArtist(id.removePrefix(ARTIST))
        id.startsWith(PLAYLIST) -> playPlaylist(id)
        id.startsWith(AUDIOBOOK) -> playBook(id)
        else -> false
    }

    // ---- playing ----

    /**
     * A voice "play …": an album, else an audiobook, else a song (in its album), else an artist's songs. A hit whose
     * title holds the whole query comes first, in that order ("play money" plays the song, not its album from the top).
     */
    private suspend fun playBest(query: String): Boolean {
        val words = MediaText.words(query)
        if (words.isEmpty()) return false
        val hits = index.search(query, artists = 1, albums = 1, songs = 1, audiobooks = 1)
        val phrase = words.joinToString(" ")
        fun titled(title: String) = phrase in MediaText.words(title).joinToString(" ")
        val album = hits.albums.firstOrNull()
        val book = hits.audiobooks.firstOrNull()
        val song = hits.songs.firstOrNull()
        return when {
            album != null && titled(album.title) -> playAlbum(album.key)
            book != null && titled(book.title) -> playBook(book.id)
            song != null && titled(song.title) -> { songs.play(song); true }
            album != null -> playAlbum(album.key)
            book != null -> playBook(book.id)
            song != null -> { songs.play(song); true }
            else -> hits.artists.firstOrNull()?.let { playArtist(it.name) } ?: false
        }
    }

    private suspend fun playAlbum(key: String): Boolean {
        val (album, tracks) = withContext(compute) {
            val tracks = MusicCatalog.albumTracks(key, media.tracks.first())
            MusicCatalog.albums(tracks).firstOrNull() to tracks
        }
        val (ids, start) = MusicCatalog.playable(tracks, tapped = null) ?: return false
        hub.playTracks(ids, start, shuffle = false, source = MusicSources.album(album ?: return false))
        return true
    }

    private suspend fun playArtist(name: String): Boolean {
        val page = withContext(compute) { MusicCatalog.artistPage(name, present()) } ?: return false
        val (ids, start) = MusicCatalog.playable(page.songs, tapped = null) ?: return false
        hub.playTracks(ids, start, shuffle = false, source = MusicSources.artist(page.name))
        return true
    }

    private suspend fun playPlaylist(id: String): Boolean {
        val playlistId = playlistId(id) ?: return false
        val playlist = playlists.all.first().firstOrNull { it.id == playlistId } ?: return false
        val items = playlists.items(playlistId).first()
        val tapped = entryOrdinal(id)?.let { ordinal -> items.indexOfFirst { it.ordinal == ordinal }.takeIf { it >= 0 } ?: return false }
        val (ids, start) = MusicCatalog.playable(items.map { it.track }, tapped) ?: return false
        hub.playTracks(ids, start, shuffle = false, source = MusicSources.playlist(playlist))
        return true
    }

    private suspend fun playBook(id: String): Boolean {
        if (media.audiobook(id)?.status != PackStatus.OK) return false
        hub.playAudiobook(id, null)
        return true
    }

    // ---- reading the library ----

    private suspend fun present(): List<Track> = Albums.present(media.tracks.first())

    private fun albums(tracks: List<Track>): List<AlbumSummary> = MediaNames.albums(tracks)

    /** The present track an `album:<key>#<trackId>` id names, if it's in that album. */
    private suspend fun song(id: String): Track? {
        val at = id.indexOf(TRACK).takeIf { it > ALBUM.length } ?: return null
        val key = id.substring(ALBUM.length, at)
        return media.track(id.substring(at + ENTRY.length))?.takeIf { it.albumKey == key && it.status != PackStatus.MISSING }
    }

    /** Present audiobooks as the shelf orders them: part-way by last played, unplayed by title, finished last. */
    private suspend fun shelf(): List<Audiobook> {
        val books = media.audiobooks.first().filter { it.status == PackStatus.OK }
        val chapters = media.allChapters.first().groupBy { it.audiobookId }
        val states = playback.observeAudiobookStates().first().associateBy { it.audiobookId }
        return withContext(compute) {
            AudiobookShelf.order(books.map { AudiobookShelf.shelfBook(it, chapters[it.id].orEmpty(), states[it.id]) }).map { it.book }
        }
    }

    /** Each recent play with its row, or a null play when its source is gone (left out). */
    private suspend fun recents(): List<Pair<RecentPlay?, MediaItem>> {
        val plays = playback.observeRecentPlays(RECENT_LIMIT).first()
        val tracks = present()
        val lists = playlists.all.first().map { it.id }.toSet()
        return plays.map { play ->
            val key = play.key
            val title = MusicSources.title(key, play.title)
            val cover: String?
            val available: Boolean
            when {
                key.startsWith(AUDIOBOOK) -> {
                    val book = media.audiobook(key)?.takeIf { it.status == PackStatus.OK }
                    available = book != null
                    cover = book?.coverPath
                }
                key.startsWith(MusicSources.PLAYLIST) -> {
                    available = MusicSources.playlistId(key) in lists
                    cover = null
                }
                else -> {
                    val source = withContext(compute) { MusicCatalog.sourceTracks(key, tracks) }
                    available = source != null
                    cover = if (key.startsWith(ALBUM)) source?.firstNotNullOfOrNull { it.coverPath } else null
                }
            }
            (if (available) play else null) to playable("$RECENT_ITEM$key", title, null, cover, MediaMetadata.MEDIA_TYPE_MIXED)
        }
    }

    private suspend fun playlistItemOf(id: String): MediaItem? {
        val playlistId = playlistId(id) ?: return null
        val playlist = playlists.all.first().firstOrNull { it.id == playlistId } ?: return null
        val ordinal = entryOrdinal(id) ?: return playlistItem(playlist)
        val entry = playlists.items(playlistId).first().firstOrNull { it.ordinal == ordinal } ?: return null
        return entryItem(playlistId, ordinal, entry.track).takeIf { entry.track.status != PackStatus.MISSING }
    }

    private fun playlistId(id: String): Long? = id.removePrefix(PLAYLIST).substringBefore(ENTRY).toLongOrNull()

    /** The entry ordinal of `playlist:<id>#<ordinal>`; null for the whole playlist. */
    private fun entryOrdinal(id: String): Int? = id.substringAfter(ENTRY, "").takeIf { it.isNotEmpty() }?.toIntOrNull()

    // ---- items ----

    private fun recentFolder() = folder(RECENT, "Recent", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    private fun musicFolder() = folder(MUSIC, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    private fun audiobooksFolder() = folder(AUDIOBOOKS, "Audiobooks", MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, grid = true)
    private fun albumsFolder() = folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, grid = true)
    private fun artistsFolder() = folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
    private fun playlistsFolder() = folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)

    private fun albumItem(a: AlbumSummary) =
        item("$ALBUM${a.key}", a.title, a.albumArtist, a.coverPath, MediaMetadata.MEDIA_TYPE_ALBUM, browsable = true, playable = true)

    /** An artist: browsable (its albums, as a grid) and playable (its songs). */
    private fun artistItem(name: String) =
        item("$ARTIST$name", name, null, null, MediaMetadata.MEDIA_TYPE_ARTIST, browsable = true, playable = true, grid = true)

    private fun songItem(t: Track) = playable(
        "$ALBUM${t.albumKey}$ENTRY${t.id}", t.title, MediaNames.trackArtistsOf(t).joinToString(", "), t.coverPath,
        MediaMetadata.MEDIA_TYPE_MUSIC, album = Albums.title(t),
    )

    private fun playlistItem(p: Playlist) =
        item("$PLAYLIST${p.id}", p.name, null, null, MediaMetadata.MEDIA_TYPE_PLAYLIST, browsable = true, playable = true)

    private fun entryItem(playlistId: Long, ordinal: Int, t: Track) = playable(
        "$PLAYLIST$playlistId$ENTRY$ordinal", t.title, MediaNames.trackArtistsOf(t).joinToString(", "), t.coverPath,
        MediaMetadata.MEDIA_TYPE_MUSIC, album = Albums.title(t),
    )

    private fun bookItem(b: Audiobook) = playable(b.id, b.title, b.author, b.coverPath, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

    private fun nothing() = item(EMPTY, "Nothing here yet", null, null, MediaMetadata.MEDIA_TYPE_MIXED, browsable = false, playable = false)

    private fun folder(id: String, title: String, type: Int, grid: Boolean = false) =
        item(id, title, null, null, type, browsable = true, playable = false, grid = grid)

    private fun playable(id: String, title: String, subtitle: String?, cover: String?, type: Int, album: String? = null) =
        item(id, title, subtitle, cover, type, browsable = false, playable = true, album = album)

    /** [grid]: this folder's children show as a grid of covers. */
    private fun item(
        id: String, title: String, subtitle: String?, cover: String?, type: Int,
        browsable: Boolean, playable: Boolean, grid: Boolean = false, album: String? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setAlbumTitle(album)
            .setArtworkUri(cover?.let(artwork::uri))
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .setMediaType(type)
            .apply {
                if (grid) setExtras(Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                })
            }
            .build())
        .build()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val MUSIC = "music"
        const val AUDIOBOOKS = "audiobooks"
        const val ALBUMS = "music/albums"
        const val ARTISTS = "music/artists"
        const val PLAYLISTS = "music/playlists"
        const val EMPTY = "empty"
        private const val ALBUM = "album:"
        private const val ARTIST = "artist:"
        private const val PLAYLIST = "playlist:"
        private const val AUDIOBOOK = "audiobook:"
        private const val RECENT_ITEM = "recent:"
        private const val ENTRY = "#"
        /** Where an album song's track id starts in its id: track ids all begin "track:" (and may hold '#'). */
        private const val TRACK = "#track:"
        private const val RECENT_LIMIT = 30
    }
}
