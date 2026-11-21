package app.granger.media

import app.granger.core.library.MediaLibrary
import app.granger.core.library.Track
import app.granger.core.player.PlayerHub
import app.granger.music.MusicCatalog
import app.granger.music.MusicSources
import kotlinx.coroutines.flow.first

/**
 * Plays one song where it belongs: its album, from that song, as the album page plays a tapped row (Search, and
 * later Android Auto). The album is read from the current catalog, so a [Track] from a stale result still plays
 * what the library holds now.
 */
class SongPlayer(private val media: MediaLibrary, private val hub: PlayerHub) {
    /** The song's album (its OK tracks in album order) from that song, as the album's PlaySource. */
    suspend fun play(track: Track) {
        val tracks = MusicCatalog.albumTracks(track.albumKey, media.tracks.first())
        val album = MusicCatalog.albums(tracks).firstOrNull() ?: return
        val tapped = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: return
        val (ids, start) = MusicCatalog.playable(tracks, tapped) ?: return
        hub.playTracks(ids, start, shuffle = false, source = MusicSources.album(album))
    }
}
