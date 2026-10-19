package app.granger.music

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicTabTest {
    @Test fun albumsArtistsSongsComeFirstAndAlbumsIsTheDefault() {
        assertThat(MusicTab.entries.map { it.title })
            .containsExactly("Albums", "Artists", "Songs", "Genres", "Playlists", "Folders").inOrder()
        assertThat(MusicTab.DEFAULT).isEqualTo(MusicTab.ALBUMS)
    }
}
