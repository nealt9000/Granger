package app.granger.music

import app.granger.core.library.Track
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicTabTest {
    @Test fun albumsArtistsSongsComeFirstAndAlbumsIsTheDefault() {
        assertThat(MusicTab.entries.map { it.title })
            .containsExactly("Albums", "Artists", "Songs", "Genres", "Playlists", "Folders").inOrder()
        assertThat(MusicTab.DEFAULT).isEqualTo(MusicTab.ALBUMS)
    }

    // Plan LV Task 4: Songs, Albums and Artists A–Z with the letter strip.

    @Test fun eachShelfsSortsAndWhichOnesFileUnderLetters() {
        assertThat(SongSort.entries.filter { it.byName }).containsExactly(SongSort.TITLE, SongSort.ARTIST, SongSort.ALBUM).inOrder()
        assertThat(AlbumSort.entries.map { it.label }).containsExactly("Title", "Artist", "Year").inOrder()
        assertThat(AlbumSort.entries.filter { it.byName }).containsExactly(AlbumSort.TITLE, AlbumSort.ARTIST).inOrder()
    }

    /** [n] songs: some with articles, digits or accents, over albums of ten and artists of fifty. */
    private fun library(n: Int): List<Track> = (0 until n).map { i ->
        val word = WORDS[i % WORDS.size]
        val title = when (i % 7) {
            0 -> "The $word ${i / WORDS.size}"
            1 -> "${i % 100} $word"
            2 -> "Émile's $word"
            else -> "$word ${i / WORDS.size}"
        }
        song("t$i", title = title, album = "A ${WORDS[(i / 10) % WORDS.size]} album ${i / 10}", albumArtists = "The ${WORDS[(i / 50) % WORDS.size]} Band ${i / 50}",
            genres = WORDS[i % 9], folder = "Music/${i / 100}/", trackNo = i % 10 + 1, date = "${1950 + i % 70}", addedAt = i.toLong())
    }

    @Test fun songsFileUnderTheirTitleArtistOrAlbumLetter() {
        val tracks = listOf(
            song("o", title = "The Odyssey", album = "Zen", albumArtists = "The Who"),
            song("n", title = "1984", album = "Abbey Road", albumArtists = "Beatles").copy(sortArtist = "Beatles, The"),
            song("e", title = "Émile", album = "An Evening", albumArtists = "ABBA"),
        ) + library(40)
        val byTitle = MusicCatalog.songSections(tracks, SongSort.TITLE)
        fun letter(sections: app.granger.library.shelf.ShelfSections<Track>, id: String) = sections.items.first { it.value.id == "track:phone:$id" }.letter
        assertThat(letter(byTitle, "o")).isEqualTo("O")
        assertThat(letter(byTitle, "n")).isEqualTo("#")
        assertThat(letter(byTitle, "e")).isEqualTo("E")
        assertThat(byTitle.letters.last()).isEqualTo("#")
        val byArtist = MusicCatalog.songSections(tracks, SongSort.ARTIST)
        assertThat(letter(byArtist, "o")).isEqualTo("W")
        assertThat(letter(byArtist, "n")).isEqualTo("B")
        val byAlbum = MusicCatalog.songSections(tracks, SongSort.ALBUM)
        assertThat(letter(byAlbum, "e")).isEqualTo("E")
        assertThat(letter(byAlbum, "o")).isEqualTo("Z")
        assertThat(MusicCatalog.songSections(tracks, SongSort.YEAR).lettered).isFalse()
        assertThat(MusicCatalog.songSections(tracks, SongSort.DATE_ADDED).lettered).isFalse()
    }

    @Test fun albumsSortByTitleArtistThenYearOrYear() {
        val tracks = listOf(
            song("a", album = "The Wall", albumArtists = "Pink Floyd", date = "1979"),
            song("b", album = "Animals", albumArtists = "Pink Floyd", date = "1977"),
            song("c", album = "Abbey Road", albumArtists = "The Beatles", date = "1969"),
            song("d", album = "1999", albumArtists = "Prince", date = "1982"),
            song("e", album = "Kind of Blue", albumArtists = "Miles Davis", date = "1959").copy(sortAlbumArtist = "Davis, Miles"),
        )
        fun titles(sort: AlbumSort) = MusicCatalog.albumSections(tracks, sort).items.map { it.value.title }
        assertThat(titles(AlbumSort.TITLE)).containsExactly("Abbey Road", "Animals", "Kind of Blue", "The Wall", "1999").inOrder()
        // Beatles (B), Davis (D), Pink Floyd by year, Prince.
        assertThat(titles(AlbumSort.ARTIST)).containsExactly("Abbey Road", "Kind of Blue", "Animals", "The Wall", "1999").inOrder()
        assertThat(titles(AlbumSort.YEAR)).containsExactly("Kind of Blue", "Abbey Road", "Animals", "The Wall", "1999").inOrder()
        // Android Auto lists albums by the same title key.
        assertThat(MusicCatalog.albumsByTitle(MusicCatalog.albums(tracks), tracks).map { it.title })
            .containsExactly("Abbey Road", "Animals", "Kind of Blue", "The Wall", "1999").inOrder()
    }

    @Test fun artistsGenresAndFoldersSkipALeadingArticle() {
        val tracks = listOf(
            song("1", albumArtists = "The Who", genres = "The Blues", folder = "The Kinks/"),
            song("2", albumArtists = "ABBA", genres = "Rock", folder = "ABBA/"),
            song("3", albumArtists = "Uriah Heep", genres = "Ambient", folder = "Zappa/"),
        )
        assertThat(MusicCatalog.artists(tracks).map { it.name }).containsExactly("ABBA", "Uriah Heep", "The Who").inOrder()
        assertThat(MusicCatalog.artistSections(tracks).items.map { it.letter }).containsExactly("A", "U", "W").inOrder()
        assertThat(MusicCatalog.genres(tracks).map { it.name }).containsExactly("Ambient", "The Blues", "Rock").inOrder()
        assertThat(MusicCatalog.folders(tracks).map { it.name }).containsExactly("ABBA", "The Kinks", "Zappa").inOrder()
    }

    @Test fun fiveThousandSongsAreShelvedInUnder200Ms() {
        val tracks = library(5_000)
        fun build() {
            for (sort in SongSort.entries) MusicCatalog.songSections(tracks, sort)
            MusicCatalog.albumSections(tracks, AlbumSort.TITLE)
            MusicCatalog.artistSections(tracks)
        }
        val coldMs = timed { build() } // every sort once, cold; then warm, as a phone's second visit would be
        val songsMs = timed { MusicCatalog.songSections(tracks, SongSort.TITLE) }
        val allMs = timed { MusicCatalog.songSections(tracks, SongSort.TITLE); MusicCatalog.albumSections(tracks, AlbumSort.TITLE); MusicCatalog.artistSections(tracks) }
        println("5,000 tracks: every sort cold $coldMs ms; Songs by title $songsMs ms; Songs, Albums and Artists $allMs ms")
        val shelf = MusicCatalog.songSections(tracks, SongSort.TITLE)
        assertThat(shelf.items).hasSize(5_000)
        assertThat(shelf.letters.last()).isEqualTo("#")
        assertThat(songsMs).isLessThan(200L)
        assertThat(allMs).isLessThan(200L)
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        val WORDS = listOf(
            "Apple", "Blue", "Crimson", "Dawn", "Echo", "Falcon", "Garden", "Harbour", "Iris", "Jasmine", "Kettle", "Lantern", "Meadow",
            "Night", "Ocean", "Pine", "Quarry", "River", "Stone", "Thunder", "Umber", "Valley", "Willow", "Xylo", "Yarrow", "Zephyr",
        )
    }
}
