package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumsTest {
    @Test fun albumKeyIgnoresCaseAndSpacing() {
        assertThat(Albums.key("Holst ", "The  Planets", "Music/a")).isEqualTo(Albums.key("holst", "the planets", "Music/b"))
        assertThat(Albums.key("Holst", "The Planets", "x")).isEqualTo("holst|the planets")
        // NFKC folds compatibility forms: full-width letters and a no-break space.
        assertThat(Albums.key("ＨＯＬＳＴ", "The Planets", "x")).isEqualTo("holst|the planets")
    }

    @Test fun untitledTracksGroupByFolder() {
        assertThat(Albums.key("Holst", " ", "Music/A")).isEqualTo("|unknown|Music/A")
        val tracks = listOf(
            musicTrack("a1", album = "", albumArtist = "", folder = "Music/A"),
            musicTrack("a2", album = "", albumArtist = "", folder = "Music/A", trackNo = 2),
            musicTrack("b1", album = "", albumArtist = "", folder = "Music/B"),
        )
        val summaries = Albums.summaries(tracks)
        assertThat(summaries.map { it.key }).containsExactly("|unknown|Music/A", "|unknown|Music/B")
        assertThat(summaries.map { it.trackCount }).containsExactly(2, 1)
        assertThat(summaries.map { it.title }.toSet()).containsExactly(Albums.UNKNOWN_ALBUM)
        assertThat(summaries.map { it.albumArtist }.toSet()).containsExactly(Albums.UNKNOWN_ARTIST)
    }

    @Test fun orderedSortsByDiscThenTrack() {
        val tracks = listOf(
            musicTrack("d2t1", disc = 2, trackNo = 1),
            musicTrack("d1t2", disc = 1, trackNo = 2),
            musicTrack("d1tNull-b", disc = 1, trackNo = null, title = "B"),
            musicTrack("d1t1", disc = 1, trackNo = 1),
            musicTrack("d1tNull-a", disc = 1, trackNo = null, title = "A"),
        )
        assertThat(Albums.ordered(tracks).map { it.id.removePrefix("track:phone:") })
            .containsExactly("d1t1", "d1t2", "d1tNull-a", "d1tNull-b", "d2t1").inOrder()
    }

    @Test fun summaryTakesYearCoverAndStatusFromItsTracks() {
        val planets = listOf(
            musicTrack("p2", album = "The Planets", albumArtist = "Holst", trackNo = 2, date = "1961-05-01", coverPath = "/c/2.jpg", status = PackStatus.MISSING),
            musicTrack("p1", album = "The Planets", albumArtist = "Holst", trackNo = 1, date = "1999", status = PackStatus.MISSING),
            musicTrack("p3", album = "The Planets", albumArtist = "Holst", trackNo = 3, date = "", durationMs = 1_000L),
        )
        val gone = musicTrack("g1", album = "Gone", albumArtist = "Bach", status = PackStatus.MISSING)
        val summaries = Albums.summaries(planets + gone)
        // Sorted by album artist: Bach before Holst.
        assertThat(summaries.map { it.title }).containsExactly("Gone", "The Planets").inOrder()
        val p = summaries[1]
        assertThat(p.key).isEqualTo("holst|the planets")
        assertThat(p.albumArtist).isEqualTo("Holst")
        assertThat(p.year).isEqualTo("1961")
        assertThat(p.coverPath).isEqualTo("/c/2.jpg")
        assertThat(p.trackCount).isEqualTo(3)
        assertThat(p.durationMs).isEqualTo(121_000L)
        assertThat(p.status).isEqualTo(PackStatus.OK)
        assertThat(summaries[0].status).isEqualTo(PackStatus.MISSING)
    }
}
