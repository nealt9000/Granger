package app.granger.music

import app.granger.core.library.Track

/** An album page's track list: plain track numbers, under a "Disc n" header when the album has more than one disc. */
sealed interface AlbumRow {
    data class Disc(val number: Int) : AlbumRow
    /** [index] is the track's place in the album's play order; [number] is blank for a track with no number. */
    data class Song(val track: Track, val index: Int, val number: String) : AlbumRow
}

object AlbumRows {
    /** [tracks] in disc/track order. A track with no disc tag counts as disc 1. */
    fun of(tracks: List<Track>): List<AlbumRow> {
        val multiDisc = tracks.map { it.disc ?: 1 }.distinct().size > 1
        val rows = mutableListOf<AlbumRow>()
        var disc: Int? = null
        tracks.forEachIndexed { i, t ->
            val d = t.disc ?: 1
            if (multiDisc && d != disc) rows += AlbumRow.Disc(d)
            disc = d
            rows += AlbumRow.Song(t, i, t.trackNo?.toString().orEmpty())
        }
        return rows
    }
}
