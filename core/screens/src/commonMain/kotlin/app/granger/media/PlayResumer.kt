package app.granger.media

import app.granger.core.library.MediaLibrary
import app.granger.core.library.PackStatus
import app.granger.core.library.Playlists
import app.granger.core.player.PlaySource
import app.granger.core.player.PlayerHub
import app.granger.music.MusicCatalog
import app.granger.music.MusicSources
import kotlinx.coroutines.flow.first

/**
 * Plays a `RecentPlay` again from the rail: an audiobook resumes at its saved position; a music list is rebuilt the
 * way its screen builds it ([MusicCatalog.sourceTracks]) and played from the top with only its playable tracks; a
 * read-aloud (`voice:<source key>`) resumes through [voice], which reopens the source at its saved sentence.
 */
class PlayResumer(
    private val media: MediaLibrary,
    private val playlists: Playlists,
    private val hub: PlayerHub,
    private val voice: suspend (sourceKey: String) -> Boolean = { false },
) {
    /** False when the source is gone, or nothing in it can play now. */
    suspend fun resume(key: String, title: String): Boolean {
        if (key.startsWith(VOICE)) return voice(key.removePrefix(VOICE))
        if (key.startsWith(AUDIOBOOK)) {
            if (media.audiobook(key)?.status != PackStatus.OK) return false
            hub.playAudiobook(key)
            return true
        }
        val tracks = MusicSources.playlistId(key)?.let { id ->
            if (playlists.all.first().none { it.id == id }) null else playlists.tracks(id).first()
        } ?: MusicCatalog.sourceTracks(key, media.tracks.first()) ?: return false
        val (ids, start) = MusicCatalog.playable(tracks, tapped = null) ?: return false
        hub.playTracks(ids, start, shuffle = false, source = PlaySource(key, title))
        return true
    }

    private companion object {
        const val AUDIOBOOK = "audiobook:"
        const val VOICE = "voice:"
    }
}
