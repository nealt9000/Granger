package app.granger.search

import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaNames
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.music.song
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The second lines of music and audiobook results, as plan M3's copy fixes them. */
class SearchRowsTest {
    @Test fun rowsUseThePlannedCopy() {
        assertThat(SearchRows.artist(ArtistHit("Gustav Holst", 3, null))).isEqualTo("Artist · 3 albums")
        assertThat(SearchRows.artist(ArtistHit("Gustav Holst", 1, null))).isEqualTo("Artist · 1 album")
        val mars = song("1", "Mars", album = "The Planets", albumArtists = "Gustav Holst", artists = "London Symphony Orchestra")
        assertThat(MediaNames.albums(listOf(mars)).single().let(SearchRows::album)).isEqualTo("Album · Gustav Holst")
        assertThat(SearchRows.song(mars)).isEqualTo("Song · London Symphony Orchestra · The Planets")
        val book = Audiobook("audiobook:phone:1", MediaOrigin.PHONE, "1", "The Jungle Book", "Rudyard Kipling", "", "", null, 0L, PackStatus.OK, 0L)
        assertThat(SearchRows.audiobook(book)).isEqualTo("Rudyard Kipling")
        assertThat(SearchRows.audiobook(book.copy(narrator = "Kara Shallenberg"))).isEqualTo("Rudyard Kipling · read by Kara Shallenberg")
    }
}
