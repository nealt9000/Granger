package app.granger.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.granger.core.library.MediaIndex
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Playlists
import app.granger.core.player.MediaBrowseTree
import app.granger.core.player.PlayerHub
import app.granger.resId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * What Android Auto browses: the shared [MediaBrowse] (Plan I6 Task 2; its doc has the ids and the rules), its plain
 * nodes mapped to Media3 `MediaItem`s with Media3's media types, the grid content style, and covers as [artwork]'s
 * content:// URIs (none outside its roots).
 */
class AutoBrowseTree(
    media: MediaLibrary,
    playlists: Playlists,
    playback: PlaybackDao,
    index: MediaIndex,
    resumer: PlayResumer,
    songs: SongPlayer,
    hub: PlayerHub,
    private val artwork: Artwork,
    /** A string resource in the phone's language (the app passes its context's getString). */
    text: (Int) -> String,
    compute: CoroutineDispatcher = Dispatchers.Default,
) : MediaBrowseTree {
    private val tree = MediaBrowse(media, playlists, playback, index, resumer, songs, hub, { res -> text(res.resId) }, compute)

    override fun root(): MediaItem = tree.root().toMediaItem()

    override suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem>? =
        tree.children(parentId, page, pageSize)?.map { it.toMediaItem() }

    override suspend fun item(id: String): MediaItem? = tree.item(id)?.toMediaItem()

    override suspend fun search(query: String): List<MediaItem> = tree.search(query).map { it.toMediaItem() }

    override suspend fun play(id: String, searchQuery: String?): Boolean = tree.play(id, searchQuery)

    override suspend fun add(id: String): Boolean = tree.add(id)

    private fun BrowseNode.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtist(subtitle)
            .setAlbumTitle(album)
            .setArtworkUri(coverPath?.let(artwork::uri))
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .setMediaType(kind.mediaType)
            .apply {
                if (grid) setExtras(Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                })
            }
            .build())
        .build()

    companion object {
        const val ROOT = MediaBrowse.ROOT
        const val RECENT = MediaBrowse.RECENT
        const val MUSIC = MediaBrowse.MUSIC
        const val AUDIOBOOKS = MediaBrowse.AUDIOBOOKS
        const val ALBUMS = MediaBrowse.ALBUMS
        const val ARTISTS = MediaBrowse.ARTISTS
        const val PLAYLISTS = MediaBrowse.PLAYLISTS
        const val EMPTY = MediaBrowse.EMPTY
    }
}

/** Media3's media type for a shared node's kind. */
private val BrowseKind.mediaType: Int get() = when (this) {
    BrowseKind.FOLDER_MIXED -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    BrowseKind.FOLDER_AUDIO_BOOKS -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
    BrowseKind.FOLDER_ALBUMS -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
    BrowseKind.FOLDER_ARTISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
    BrowseKind.FOLDER_PLAYLISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
    BrowseKind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
    BrowseKind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
    BrowseKind.MUSIC -> MediaMetadata.MEDIA_TYPE_MUSIC
    BrowseKind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
    BrowseKind.AUDIO_BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
    BrowseKind.MIXED -> MediaMetadata.MEDIA_TYPE_MIXED
}
