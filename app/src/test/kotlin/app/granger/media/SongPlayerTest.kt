package app.granger.media

import app.granger.core.library.Albums
import app.granger.core.library.PackStatus
import app.granger.core.player.PlaySource
import app.granger.music.FakeMediaLibrary
import app.granger.music.song
import app.granger.player.FakePlayerHub
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SongPlayerTest {
    private val hub = FakePlayerHub()

    @Test fun songPlaysItsAlbumFromThatSong() = runTest {
        val one = song("1", "Mars", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 1)
        val two = song("2", "Venus", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 2)
        val three = song("3", "Mercury", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 3)
        val other = song("4", "Money", album = "The Dark Side of the Moon", albumArtists = "Pink Floyd", trackNo = 1)
        val media = FakeMediaLibrary(listOf(three, other, one, two))
        SongPlayer(media, hub).play(two)
        val key = Albums.key("Gustav Holst", "The Planets", "Music/")
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf(one.id, two.id, three.id), 1, false, PlaySource("album:$key", "The Planets")),
        )
    }

    @Test fun missingSongsInTheAlbumAreSkipped() = runTest {
        val one = song("1", "Mars", album = "The Planets", trackNo = 1, status = PackStatus.MISSING)
        val two = song("2", "Venus", album = "The Planets", trackNo = 2)
        val three = song("3", "Mercury", album = "The Planets", trackNo = 3)
        val media = FakeMediaLibrary(listOf(one, two, three))
        SongPlayer(media, hub).play(three)
        assertThat(hub.plays.single().trackIds).containsExactly(two.id, three.id).inOrder()
        assertThat(hub.plays.single().startIndex).isEqualTo(1)
        // A missing song itself plays nothing.
        SongPlayer(media, hub).play(one)
        assertThat(hub.plays).hasSize(1)
    }
}
