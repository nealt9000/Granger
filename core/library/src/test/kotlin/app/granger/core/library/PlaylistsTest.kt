package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistsTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val media = RoomMediaLibrary(db, now = { clock })
    private val playlists = RoomPlaylists(db, now = { clock })
    private val dao = db.playbackDao()

    @After fun close() = db.close()

    private suspend fun scan(vararg refs: String) =
        media.sync(MediaOrigin.PHONE, ScanResult(refs.map { musicTrack(it) }, emptyList()))

    private fun id(ref: String) = "track:phone:$ref"

    private suspend fun ordinals(playlistId: Long) = dao.entries(playlistId).map { it.ordinal }

    @Test fun playlistSurvivesTrackGoingMissingAndBack() = runTest {
        scan("a", "b", "c")
        val list = playlists.create("Mix", listOf(id("a"), id("b"), id("c")))

        scan("a", "c") // b's file is gone
        val whileMissing = playlists.tracks(list).first()
        assertThat(whileMissing.map { it.id }).containsExactly(id("a"), id("b"), id("c")).inOrder()
        assertThat(whileMissing[1].status).isEqualTo(PackStatus.MISSING)

        scan("a", "b", "c") // and back
        val back = playlists.tracks(list).first()
        assertThat(back.map { it.id }).containsExactly(id("a"), id("b"), id("c")).inOrder()
        assertThat(back.map { it.status }).containsExactly(PackStatus.OK, PackStatus.OK, PackStatus.OK)
    }

    @Test fun createWithTracksKeepsOrder() = runTest {
        scan("a", "b", "c")
        val list = playlists.create("  Road trip ", listOf(id("c"), id("a"), id("b"), id("a")))
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("c"), id("a"), id("b"), id("a")).inOrder()
        assertThat(ordinals(list)).containsExactly(0, 1, 2, 3).inOrder()
        val made = playlists.all.first().single()
        assertThat(made.name).isEqualTo("Road trip")
        assertThat(made.createdAt).isEqualTo(1_000L)

        clock = 2_000L
        playlists.add(list, listOf(id("b")))
        assertThat(playlists.tracks(list).first().map { it.id }.last()).isEqualTo(id("b"))
        assertThat(ordinals(list)).containsExactly(0, 1, 2, 3, 4).inOrder()
        assertThat(playlists.all.first().single().updatedAt).isEqualTo(2_000L)
    }

    @Test fun moveReordersOrdinals() = runTest {
        scan("a", "b", "c", "d")
        val list = playlists.create("Mix", listOf(id("a"), id("b"), id("c"), id("d")))

        playlists.move(list, from = 0, to = 2)
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("b"), id("c"), id("a"), id("d")).inOrder()
        assertThat(ordinals(list)).containsExactly(0, 1, 2, 3).inOrder()

        playlists.move(list, from = 3, to = 0)
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("d"), id("b"), id("c"), id("a")).inOrder()
        assertThat(ordinals(list)).containsExactly(0, 1, 2, 3).inOrder()

        playlists.move(list, from = 1, to = 9) // out of range: nothing changes
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("d"), id("b"), id("c"), id("a")).inOrder()
    }

    @Test fun removeKeepsOrdinalsDense() = runTest {
        scan("a", "b", "c", "d")
        val list = playlists.create("Mix", listOf(id("a"), id("b"), id("c"), id("d")))
        playlists.remove(list, ordinal = 1)
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("a"), id("c"), id("d")).inOrder()
        assertThat(ordinals(list)).containsExactly(0, 1, 2).inOrder()
        playlists.remove(list, ordinal = 2)
        playlists.remove(list, ordinal = 7) // no such entry
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("a"), id("c")).inOrder()
        assertThat(ordinals(list)).containsExactly(0, 1).inOrder()
    }

    @Test fun renameAndDelete() = runTest {
        scan("a")
        val keep = playlists.create("Keep", listOf(id("a")))
        val gone = playlists.create("Gone", listOf(id("a")))
        playlists.rename(keep, " Kept ")
        playlists.delete(gone)
        assertThat(playlists.all.first().map { it.name }).containsExactly("Kept")
        assertThat(dao.entries(gone)).isEmpty()
        assertThat(playlists.tracks(keep).first().map { it.id }).containsExactly(id("a"))
    }

    @Test fun entriesWithNoTrackRowAreSkipped() = runTest {
        scan("a")
        val list = playlists.create("Mix", listOf(id("a"), id("never-scanned")))
        assertThat(playlists.tracks(list).first().map { it.id }).containsExactly(id("a"))
    }
}
