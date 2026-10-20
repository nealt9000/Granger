package app.granger.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.Albums
import app.granger.core.library.AudiobookState
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaIndex
import app.granger.core.library.PackStatus
import app.granger.core.library.RecentPlay
import app.granger.core.player.PlaySource
import app.granger.music.FakeMediaLibrary
import app.granger.music.FakePlaylists
import app.granger.music.song
import app.granger.player.FakePlayerHub
import app.granger.player.audiobook
import app.granger.player.chapter
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBrowseTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val playback = db.playbackDao()
    private val hub = FakePlayerHub()
    private lateinit var covers: File
    private lateinit var artwork: Artwork

    private val holstCover by lazy { File(covers, "holst.jpg").apply { writeBytes(byteArrayOf(1)) }.path }
    private val mars by lazy { song("1", "Mars", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 1).copy(coverPath = holstCover) }
    private val venus by lazy { song("2", "Venus", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 2).copy(coverPath = holstCover) }
    private val jupiter by lazy { song("3", "Jupiter", album = "The Planets", albumArtists = "Gustav Holst", trackNo = 3).copy(coverPath = holstCover) }
    private val money = song("4", "Money", album = "The Dark Side of the Moon", albumArtists = "Pink Floyd", trackNo = 6)
    private val time = song("5", "Time", album = "The Dark Side of the Moon", albumArtists = "Pink Floyd", trackNo = 4)
    private val gone = song("6", "Lost Song", album = "Atlantis", albumArtists = "Nobody", status = PackStatus.MISSING)
    private val wicked = audiobook("wicked", "Wicked", author = "Gregory Maguire")
    private val jungle = audiobook("jungle", "The Jungle Book", author = "Rudyard Kipling")
    private val annals = audiobook("annals", "Annals", author = "Tacitus")
    private val lostBook = audiobook("lost", "A Lost Book").copy(status = PackStatus.MISSING)

    private val planetsKey = Albums.key("Gustav Holst", "The Planets", "Music/")
    private val moonKey = Albums.key("Pink Floyd", "The Dark Side of the Moon", "Music/")

    private lateinit var media: FakeMediaLibrary
    private lateinit var playlists: FakePlaylists

    @Before fun setUp() {
        covers = tmp.newFolder("media-covers")
        artwork = Artwork({ listOf(covers) }, { ByteArray(32) { it.toByte() } })
        media = FakeMediaLibrary(listOf(jupiter, money, mars, gone, time, venus))
        media.bookList.value = listOf(wicked, jungle, annals, lostBook)
        media.chapterList.value = listOf(chapter(wicked, 0, 60_000), chapter(jungle, 0, 60_000), chapter(annals, 0, 60_000))
        playlists = FakePlaylists(media)
    }

    @After fun close() = db.close()

    private fun TestScope.tree(): AutoBrowseTree {
        val index = MediaIndex(media, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        return AutoBrowseTree(
            media, playlists, playback, index, PlayResumer(media, playlists, hub), SongPlayer(media, hub), hub, artwork,
        )
    }

    private suspend fun AutoBrowseTree.ids(parent: String, page: Int = 0, size: Int = 100) = children(parent, page, size)!!.map { it.mediaId }
    private fun MediaItem.title() = mediaMetadata.title.toString()

    @Test fun rootHasRecentMusicAndAudiobooks() = runTest {
        val tree = tree()
        val root = tree.root()
        assertThat(root.mediaMetadata.isBrowsable).isTrue()
        assertThat(root.mediaMetadata.isPlayable).isFalse()
        val tabs = tree.children(root.mediaId, 0, 10)!!
        assertThat(tabs.map { it.mediaId }).containsExactly("recent", "music", "audiobooks").inOrder()
        assertThat(tabs.map { it.title() }).containsExactly("Recent", "Music", "Audiobooks").inOrder()
        assertThat(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false }).isTrue()
        assertThat(tabs[2].mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
        assertThat(tabs[2].mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    }

    @Test fun musicHasAlbumsArtistsPlaylists() = runTest {
        val music = tree().children("music", 0, 10)!!
        assertThat(music.map { it.mediaId }).containsExactly("music/albums", "music/artists", "music/playlists").inOrder()
        assertThat(music.map { it.title() }).containsExactly("Albums", "Artists", "Playlists").inOrder()
        assertThat(music.map { it.mediaMetadata.mediaType }).containsExactly(
            MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
        ).inOrder()
    }

    @Test fun albumsAreAGridByTitleWithArtwork() = runTest {
        val tree = tree()
        val folder = tree.item("music/albums")!!
        assertThat(folder.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        val albums = tree.children("music/albums", 0, 10)!!
        // By title: "The Dark Side…" before "The Planets"; Atlantis (all MISSING) is left out.
        assertThat(albums.map { it.mediaId }).containsExactly("album:$moonKey", "album:$planetsKey").inOrder()
        val planets = albums[1]
        assertThat(planets.title()).isEqualTo("The Planets")
        assertThat(planets.mediaMetadata.subtitle.toString()).isEqualTo("Gustav Holst")
        assertThat(planets.mediaMetadata.isBrowsable).isTrue()
        assertThat(planets.mediaMetadata.isPlayable).isTrue()
        assertThat(planets.mediaMetadata.artworkUri).isEqualTo(artwork.uri(holstCover))
        assertThat(planets.mediaMetadata.artworkUri!!.scheme).isEqualTo("content")
        assertThat(albums[0].mediaMetadata.artworkUri).isNull()          // no cover: no artwork
        assertThat(planets.localConfiguration).isNull()                   // never a URI to play
    }

    @Test fun albumChildrenAreItsSongsPlayableFromThere() = runTest {
        val songs = tree().children("album:$planetsKey", 0, 10)!!
        assertThat(songs.map { it.mediaId }).containsExactly(
            "album:$planetsKey#${mars.id}", "album:$planetsKey#${venus.id}", "album:$planetsKey#${jupiter.id}",
        ).inOrder()
        assertThat(songs.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false }).isTrue()
        assertThat(songs[1].title()).isEqualTo("Venus")
        assertThat(songs[1].mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_MUSIC)
    }

    @Test fun artistChildrenAreItsAlbums() = runTest {
        val tree = tree()
        assertThat(tree.ids("music/artists")).containsExactly("artist:Gustav Holst", "artist:Pink Floyd").inOrder()
        assertThat(tree.ids("artist:Pink Floyd")).containsExactly("album:$moonKey")
        assertThat(tree.ids("artist:gustav holst")).containsExactly("album:$planetsKey")
        assertThat(tree.children("artist:Nobody", 0, 10)).isNull()       // only a MISSING album
    }

    @Test fun audiobooksAreLastPlayedFirst() = runTest {
        playback.upsertAudiobookState(state(wicked.id, at = 100L))
        playback.upsertAudiobookState(state(jungle.id, at = 200L))
        val books = tree().children("audiobooks", 0, 10)!!
        // Part-way by last played, then unplayed by title; the MISSING book is left out.
        assertThat(books.map { it.mediaId }).containsExactly(jungle.id, wicked.id, annals.id).inOrder()
        assertThat(books[0].mediaMetadata.isPlayable).isTrue()
        assertThat(books[0].mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        assertThat(books[0].mediaMetadata.subtitle.toString()).isEqualTo("Rudyard Kipling")
    }

    @Test fun pagingFollowsPageAndPageSize() = runTest {
        val tree = tree()
        assertThat(tree.ids("audiobooks", page = 0, size = 2)).containsExactly(annals.id, jungle.id).inOrder()
        assertThat(tree.ids("audiobooks", page = 1, size = 2)).containsExactly(wicked.id)
        assertThat(tree.ids("audiobooks", page = 2, size = 2)).isEmpty()
    }

    @Test fun missingItemsAreLeftOut() = runTest {
        val tree = tree()
        assertThat(tree.ids("album:${planetsKey}")).doesNotContain("album:$planetsKey#${gone.id}")
        assertThat(tree.item("album:${gone.albumKey}")).isNull()
        assertThat(tree.item(lostBook.id)).isNull()
        val id = playlists.create("Mix", listOf(gone.id, money.id))
        assertThat(tree.ids("playlist:$id")).containsExactly("playlist:$id#1")
        // An empty list says so.
        media.bookList.value = emptyList()
        val empty = tree.children("audiobooks", 0, 10)!!
        assertThat(empty.map { it.title() }).containsExactly("Nothing here yet")
        assertThat(empty.single().mediaMetadata.isPlayable).isFalse()
    }

    @Test fun unknownIdIsNull() = runTest {
        val tree = tree()
        assertThat(tree.children("nope", 0, 10)).isNull()
        assertThat(tree.children("album:nope", 0, 10)).isNull()
        assertThat(tree.children("playlist:99", 0, 10)).isNull()
        assertThat(tree.item("album:$planetsKey#track:phone:nope")).isNull()
        assertThat(tree.play("nope")).isFalse()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun playingASongPlaysItsAlbumFromIt() = runTest {
        val tree = tree()
        assertThat(tree.play("album:$planetsKey#${venus.id}")).isTrue()
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf(mars.id, venus.id, jupiter.id), 1, false, PlaySource("album:$planetsKey", "The Planets")),
        )
        // A whole album plays from its first song; a playlist entry from that entry.
        assertThat(tree.play("album:$moonKey")).isTrue()
        assertThat(hub.plays.last().trackIds).containsExactly(time.id, money.id).inOrder()
        assertThat(hub.plays.last().startIndex).isEqualTo(0)
        val id = playlists.create("Mix", listOf(money.id, gone.id, mars.id))
        assertThat(tree.play("playlist:$id#2")).isTrue()
        assertThat(hub.plays.last()).isEqualTo(FakePlayerHub.Play(listOf(money.id, mars.id), 1, false, PlaySource("playlist:$id", "Mix")))
    }

    @Test fun playingAnAudiobookResumesIt() = runTest {
        val tree = tree()
        assertThat(tree.play(wicked.id)).isTrue()
        assertThat(hub.bookPlays.single()).isEqualTo(wicked.id to null)
        assertThat(tree.play(lostBook.id)).isFalse()
    }

    @Test fun playingARecentResumesIt() = runTest {
        playback.upsertRecentPlay(RecentPlay("album:$planetsKey", "The Planets", 300L))
        playback.upsertRecentPlay(RecentPlay(jungle.id, "The Jungle Book", 200L))
        val tree = tree()
        val recent = tree.children("recent", 0, 10)!!
        assertThat(recent.map { it.mediaId }).containsExactly("recent:album:$planetsKey", "recent:${jungle.id}").inOrder()
        assertThat(recent[0].mediaMetadata.artworkUri).isEqualTo(artwork.uri(holstCover))
        assertThat(recent[0].mediaMetadata.isPlayable).isTrue()

        assertThat(tree.play("recent:${jungle.id}")).isTrue()
        assertThat(hub.bookPlays.single()).isEqualTo(jungle.id to null)
        assertThat(tree.play("recent:album:$planetsKey")).isTrue()
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf(mars.id, venus.id, jupiter.id), 0, false, PlaySource("album:$planetsKey", "The Planets")),
        )
    }

    @Test fun voicePlayPicksTheBestHit() = runTest {
        val tree = tree()
        assertThat(tree.play("", "holst")).isTrue()
        assertThat(hub.plays.single().source).isEqualTo(PlaySource("album:$planetsKey", "The Planets"))
        assertThat(hub.plays.single().startIndex).isEqualTo(0)
        assertThat(tree.play("", "wicked")).isTrue()
        assertThat(hub.bookPlays.single()).isEqualTo(wicked.id to null)
        // A song title alone: the song, in its album.
        assertThat(tree.play("", "money")).isTrue()
        assertThat(hub.plays.last()).isEqualTo(FakePlayerHub.Play(listOf(time.id, money.id), 1, false, PlaySource("album:$moonKey", "The Dark Side of the Moon")))
        assertThat(tree.play("", "zzzz")).isFalse()
    }

    @Test fun searchReturnsPlayableHits() = runTest {
        val hits = tree().search("holst")
        assertThat(hits.map { it.mediaId }).containsExactly(
            "artist:Gustav Holst", "album:$planetsKey",
            "album:$planetsKey#${jupiter.id}", "album:$planetsKey#${mars.id}", "album:$planetsKey#${venus.id}",
        ).inOrder()
        assertThat(hits.all { it.mediaMetadata.isPlayable == true }).isTrue()
        assertThat(tree().search("kipling").map { it.mediaId }).containsExactly(jungle.id)
        assertThat(tree().search("")).isEmpty()
    }

    private fun state(id: String, at: Long) = AudiobookState(id, "track:phone:x", 1_000L, 1f, false, 0, false, at)
}
