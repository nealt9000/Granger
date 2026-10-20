package app.granger.core.library

import java.util.Locale

/**
 * The artist and genre name rules: how a track's `"; "`-joined tags become names, so the music screens, the media
 * index and Android Auto agree.
 */
object MediaNames {
    const val UNKNOWN_GENRE = "Unknown genre"
    private const val JOIN = "; "

    /** "; "-joined tag values, trimmed, blanks dropped. */
    fun split(joined: String): List<String> = joined.split(JOIN).map { it.trim() }.filter { it.isNotEmpty() }

    /** A track's album artists; with none tagged, its track artists stand in (Auxio). */
    fun albumArtistsOf(t: Track): List<String> =
        split(t.albumArtists).ifEmpty { split(t.artists) }.ifEmpty { listOf(Albums.UNKNOWN_ARTIST) }

    /** A track's own artists; with none tagged, its album artists stand in. */
    fun trackArtistsOf(t: Track): List<String> =
        split(t.artists).ifEmpty { split(t.albumArtists) }.ifEmpty { listOf(Albums.UNKNOWN_ARTIST) }

    fun genresOf(t: Track): List<String> = split(t.genres).ifEmpty { listOf(UNKNOWN_GENRE) }

    /** Artists are matched without regard to case or surrounding space. */
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Albums as [Albums.summaries] builds them, with the track artists named where no album artist is tagged. */
    fun albums(tracks: List<Track>): List<AlbumSummary> {
        val byKey = tracks.groupBy { it.albumKey }
        return Albums.summaries(tracks).map { a ->
            if (a.albumArtist != Albums.UNKNOWN_ARTIST) a
            else a.copy(albumArtist = byKey[a.key].orEmpty().flatMap(::albumArtistsOf).distinctBy(::nameKey).joinToString(JOIN))
        }
    }
}
