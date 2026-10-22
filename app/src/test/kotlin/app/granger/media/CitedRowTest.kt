package app.granger.media

import app.granger.audiobooks.citedChapterIndex
import app.granger.music.AlbumRow
import app.granger.music.citedSongIndex
import app.granger.music.song
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CitedRowTest {
    @Test fun aRouteTrackIsMarkedAfterTheAlbumHeaderRows() {
        val rows = listOf(AlbumRow.Disc(1), AlbumRow.Song(song("a"), 0, "1"), AlbumRow.Song(song("b"), 1, "2"))
        val b = (rows[2] as AlbumRow.Song).track.id
        // Header and Play/Shuffle come first, then the download controls when there are some.
        assertThat(citedSongIndex(rows, b, hasDownload = false)).isEqualTo(4)
        assertThat(citedSongIndex(rows, b, hasDownload = true)).isEqualTo(5)
        assertThat(citedSongIndex(rows, "track:phone:gone", hasDownload = false)).isNull()
        assertThat(citedSongIndex(rows, null, hasDownload = false)).isNull()
    }

    @Test fun aRouteChapterIsMarkedAfterTheAudiobookHeaderRows() {
        val ordinals = listOf(0, 1, 2)
        // Header, progress, buttons and the "Chapters" title, plus download controls and a description when shown.
        assertThat(citedChapterIndex(ordinals, 0, hasDownload = false, hasDescription = false)).isEqualTo(4)
        assertThat(citedChapterIndex(ordinals, 2, hasDownload = true, hasDescription = true)).isEqualTo(8)
        assertThat(citedChapterIndex(ordinals, 9, hasDownload = false, hasDescription = false)).isNull()
        assertThat(citedChapterIndex(ordinals, null, hasDownload = false, hasDescription = false)).isNull()
    }
}
