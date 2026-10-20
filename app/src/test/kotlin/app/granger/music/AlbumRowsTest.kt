package app.granger.music

import app.granger.player.track
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlbumRowsTest {
    private fun numbered(id: String, disc: Int?, n: Int?) = track(id).copy(disc = disc, trackNo = n)

    @Test fun oneDiscHasPlainNumbersAndNoHeader() {
        val rows = AlbumRows.of(listOf(numbered("a", 1, 1), numbered("b", 1, 2), numbered("c", null, 3)))
        assertThat(rows.map { (it as AlbumRow.Song).number }).containsExactly("1", "2", "3").inOrder()
    }

    @Test fun twoDiscsGetHeadersAndRestartTheirNumbers() {
        val rows = AlbumRows.of(listOf(numbered("a", 1, 1), numbered("b", 1, 2), numbered("c", 2, 1), numbered("d", 2, 2)))
        assertThat(rows.map { if (it is AlbumRow.Disc) "Disc ${it.number}" else (it as AlbumRow.Song).number })
            .containsExactly("Disc 1", "1", "2", "Disc 2", "1", "2").inOrder()
        // Play order still runs through the whole album.
        assertThat(rows.filterIsInstance<AlbumRow.Song>().map { it.index }).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test fun aTrackWithNoNumberShowsNoNumber() {
        val rows = AlbumRows.of(listOf(numbered("a", null, null)))
        assertThat((rows.single() as AlbumRow.Song).number).isEmpty()
    }
}
