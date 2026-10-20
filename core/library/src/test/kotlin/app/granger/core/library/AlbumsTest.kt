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
        assertThat(summaries.map { it.title }).containsExactly("A", "B")
        assertThat(summaries.map { it.albumArtist }.toSet()).containsExactly(Albums.UNKNOWN_ARTIST)
    }

    @Test fun untaggedAlbumIsNamedAfterItsFolder() {
        // The real case: six untagged Holst files in one folder.
        val folder = "Music/Holst - The Planets (LSO 1922-24)/"
        val holst = (1..6).map { musicTrack("h$it", album = "", albumArtist = "", folder = folder, trackNo = it) }
        val summary = Albums.summaries(holst).single()
        assertThat(summary.title).isEqualTo("Holst - The Planets (LSO 1922-24)")
        assertThat(summary.key).isEqualTo("|unknown|$folder")          // the key is unchanged
        assertThat(summary.albumArtist).isEqualTo(Albums.UNKNOWN_ARTIST)
        assertThat(summary.trackCount).isEqualTo(6)
        assertThat(Albums.title(holst[0])).isEqualTo("Holst - The Planets (LSO 1922-24)")
        assertThat(Albums.untaggedTitle(summary.key)).isEqualTo("Holst - The Planets (LSO 1922-24)")
        assertThat(Albums.title("", "Holst")).isEqualTo("Holst")         // a top-level folder
    }

    @Test fun untaggedAlbumInABlankFolderFallsBackToUnknownAlbum() {
        assertThat(Albums.title("", "")).isEqualTo(Albums.UNKNOWN_ALBUM)
        assertThat(Albums.title(" ", "/")).isEqualTo(Albums.UNKNOWN_ALBUM)
        val summary = Albums.summaries(listOf(musicTrack("x", album = "", albumArtist = "", folder = ""))).single()
        assertThat(summary.title).isEqualTo(Albums.UNKNOWN_ALBUM)
    }

    @Test fun taggedAlbumsKeepTheirTitleAndSortWithTheFolderNamedOnes() {
        val tagged = musicTrack("t", album = " The Planets ", albumArtist = "", folder = "Music/Zzz/")
        assertThat(Albums.title(tagged)).isEqualTo("The Planets")
        assertThat(Albums.untaggedTitle(tagged.albumKey)).isNull()
        assertThat(Albums.untaggedTitle("holst|the planets")).isNull()
        // Same (unknown) album artist: sorted by title, the folder name standing in for the missing tag.
        val untagged = listOf(
            musicTrack("m", album = "", albumArtist = "", folder = "Music/Mars/"),
            musicTrack("a", album = "", albumArtist = "", folder = "Music/Zzz/Apollo/"),
        )
        assertThat(Albums.summaries(untagged + tagged).map { it.title }).containsExactly("Apollo", "Mars", "The Planets").inOrder()
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

    @Test fun summaryTakesYearAndCoverFromItsTracks() {
        val planets = listOf(
            musicTrack("p2", album = "The Planets", albumArtist = "Holst", trackNo = 2, date = "1961-05-01", coverPath = "/c/2.jpg"),
            musicTrack("p1", album = "The Planets", albumArtist = "Holst", trackNo = 1, date = "1999"),
            musicTrack("p3", album = "The Planets", albumArtist = "Holst", trackNo = 3, date = "", durationMs = 1_000L),
        )
        val bach = musicTrack("b1", album = "Goldberg Variations", albumArtist = "Bach")
        val summaries = Albums.summaries(planets + bach)
        // Sorted by album artist: Bach before Holst.
        assertThat(summaries.map { it.title }).containsExactly("Goldberg Variations", "The Planets").inOrder()
        val p = summaries[1]
        assertThat(p.key).isEqualTo("holst|the planets")
        assertThat(p.albumArtist).isEqualTo("Holst")
        assertThat(p.year).isEqualTo("1961")
        assertThat(p.coverPath).isEqualTo("/c/2.jpg")
        assertThat(p.trackCount).isEqualTo(3)
        assertThat(p.durationMs).isEqualTo(121_000L)
        assertThat(p.status).isEqualTo(PackStatus.OK)
    }

    /** A MISSING track isn't part of its album; an album whose tracks are all MISSING isn't there at all. */
    @Test fun summariesCountOnlyPresentTracks() {
        val planets = listOf(
            musicTrack("p1", album = "The Planets", albumArtist = "Holst", trackNo = 1, date = "1999"),
            musicTrack("p2", album = "The Planets", albumArtist = "Holst", trackNo = 2, date = "1961", coverPath = "/c/2.jpg", status = PackStatus.MISSING),
        )
        val gone = musicTrack("g1", album = "Gone", albumArtist = "Bach", status = PackStatus.MISSING)
        val summaries = Albums.summaries(planets + gone)
        assertThat(summaries.map { it.title }).containsExactly("The Planets")
        val p = summaries.single()
        assertThat(p.trackCount).isEqualTo(1)
        assertThat(p.durationMs).isEqualTo(planets[0].durationMs)
        assertThat(p.year).isEqualTo("1999")
        assertThat(p.coverPath).isNull()
    }

    @Test fun itemKeyNeverCollidesWithATagKey() {
        val item = Albums.itemKey("archive-org", "x")
        assertThat(item).isEqualTo("|item|archive-org:x")
        assertThat(item).isNotEqualTo(Albums.key("archive-org", "x", ""))
        // A tagged album with no album artist whose title spells an item key still gets a key of its own.
        assertThat(Albums.key("", "item|archive-org:x", "")).isNotEqualTo(item)
        assertThat(Albums.key(" ", "Item|Archive-Org:X", "Music/")).isNotEqualTo(item)
        assertThat(Albums.untaggedTitle(item)).isNull()
    }

    /** Downloaded tracks are grouped by their origin item alone: folders and tags don't split or merge them. */
    @Test fun oneItemKeyIsOneAlbum() {
        val key = Albums.itemKey("archive-org", "holst-the-planets-holst-london-symphony-orchestra-1922-1924")
        val planets = (1..3).map { n ->
            musicTrack("h$n", album = "The Planets", albumArtist = "Gustav Holst", folder = "music/archive-org/holst/", trackNo = n)
                .copy(albumKey = key, origin = MediaOrigin.ARCHIVE_ORG)
        }
        // A phone album with the same tags is a different album.
        val phone = musicTrack("p1", album = "The Planets", albumArtist = "Gustav Holst")
        val summaries = Albums.summaries(planets + phone)
        assertThat(summaries).hasSize(2)
        val item = summaries.single { it.key == key }
        assertThat(item.trackCount).isEqualTo(3)
        assertThat(item.title).isEqualTo("The Planets")
        assertThat(item.albumArtist).isEqualTo("Gustav Holst")
    }
}
