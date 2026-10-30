package app.granger.music

import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.core.library.Albums
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaOrigin
import app.granger.core.library.MediaType
import app.granger.sources.ItemStatus
import kotlinx.coroutines.flow.flowOf
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.core.library.Track
import app.granger.core.player.PlaySource
import app.granger.player.FakePlayerHub
import app.granger.library.shelf.LibraryViewPrefs
import app.granger.library.shelf.ShelfKind
import app.granger.library.shelf.ShelfView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MusicViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val media = FakeMediaLibrary()
    private val shelves = FakeShelves()
    private val playlists = FakePlaylists(media)
    private val hub = FakePlayerHub()

    private val removed = mutableListOf<String>()

    private val viewPrefs = LibraryViewPrefs()

    private fun TestScope.vm(collectionId: String? = null): MusicViewModel {
        val vm = MusicViewModel(collectionId, media, shelves, playlists, hub, downloads = flowOf(emptyList()), remover = { removed += it }, viewPrefs = viewPrefs)
        backgroundScope.launch { vm.ui.collect {} }
        return vm
    }

    private fun row(id: String, name: String, tracks: List<Track>) = CollectionRow(
        ShelfCollection(id, name, "music", userCreated = false, defaultKey = id), packs = emptyList(),
        albums = Albums.summaries(tracks),
    )

    private val music = MediaType("music", "Music", true, 1)

    @Test fun allMusicIgnoresCollectionsButCollectionScopes() = runTest(main.dispatcher) {
        val jazz = listOf(song("j1", album = "Kind of Blue", albumArtists = "Miles", genres = "Jazz", folder = "Jazz/"))
        val rock = listOf(
            song("r1", album = "Abbey Road", albumArtists = "Beatles", genres = "Rock", folder = "Rock/"),
            song("r2", album = "Abbey Road", albumArtists = "Beatles", genres = "Rock", folder = "Rock/"),
        )
        media.trackList.value = jazz + rock
        shelves.shelfList.value = listOf(Shelf(music, listOf(row("c-jazz", "Jazz shelf", jazz), row("c-rock", "Rock shelf", rock))))
        val mix = playlists.create("Mix", listOf("track:phone:j1", "track:phone:r1"))

        val all = vm(null).ui.filterNotNull().first()
        assertThat(all.title).isEqualTo("All music")
        assertThat(all.songs.map { it.id }).containsExactly("track:phone:j1", "track:phone:r1", "track:phone:r2")
        assertThat(all.albums.map { it.title }).containsExactly("Kind of Blue", "Abbey Road")
        assertThat(all.playlists.map { it.id }).containsExactly(mix)

        val scoped = vm("c-rock").ui.filterNotNull().first()
        assertThat(scoped.title).isEqualTo("Rock shelf")
        assertThat(scoped.songs.map { it.id }).containsExactly("track:phone:r1", "track:phone:r2")
        assertThat(scoped.albums.map { it.title }).containsExactly("Abbey Road")
        assertThat(scoped.artists.map { it.name }).containsExactly("Beatles")
        assertThat(scoped.genres.map { it.name }).containsExactly("Rock")
        assertThat(scoped.folders.map { it.path }).containsExactly("Rock/")
        // Playlists are global: every playlist shows inside any collection.
        assertThat(scoped.playlists.map { it.id }).containsExactly(mix)
    }

    @Test fun artistsMergeAlbumArtistsAndTrackArtists() = runTest(main.dispatcher) {
        media.trackList.value = listOf(
            song("a1", album = "Alpha", albumArtists = "Ann", artists = "Ann; Bob"),
            song("a2", album = "Alpha", albumArtists = "Ann", artists = "Ann"),
            song("b1", album = "Beta", albumArtists = "", artists = "Cal"), // no album artist: the track artist stands in
            song("g1", album = "Gamma", albumArtists = "Dee", artists = "bob", releaseType = "ep"),
            song("d1", album = "Delta", albumArtists = "Dee", artists = "Dee", releaseType = "album; live"),
            song("e1", album = "Epsilon", albumArtists = "Dee", artists = "Dee"),
        )
        val vm = vm()
        val ui = vm.ui.filterNotNull().first()
        assertThat(ui.artists.map { it.name }).containsExactly("Ann", "Bob", "Cal", "Dee").inOrder()

        val bob = vm.artist("BOB").filterNotNull().first()
        assertThat(bob.sections).isEmpty()
        assertThat(bob.appearsOn.map { it.title }).containsExactly("Alpha", "Gamma")
        assertThat(bob.songs.map { it.id }).containsExactly("track:phone:a1", "track:phone:g1")

        val cal = vm.artist("Cal").filterNotNull().first()
        assertThat(cal.sections.map { it.group to it.albums.map { a -> a.title } }).containsExactly(ReleaseGroup.ALBUMS to listOf("Beta"))

        val dee = vm.artist("Dee").filterNotNull().first()
        assertThat(dee.sections.map { it.group to it.albums.map { a -> a.title } }).containsExactly(
            ReleaseGroup.ALBUMS to listOf("Epsilon"),
            ReleaseGroup.EPS to listOf("Gamma"),
            ReleaseGroup.LIVE to listOf("Delta"),
        ).inOrder()
        assertThat(dee.appearsOn).isEmpty()
    }

    /**
     * The walkthrough's WhatsApp rows: MISSING tracks aren't in any tab or page, and an album, artist, genre or folder
     * that only had MISSING tracks is gone. A playlist still lists its MISSING songs (dimmed in place).
     */
    @Test fun missingTracksAreHiddenFromBrowsingButNotFromPlaylists() = runTest(main.dispatcher) {
        val whatsapp = song("w1", album = "", albumArtists = "", artists = "WhatsApp", genres = "Voice", folder = "WhatsApp/", status = PackStatus.MISSING)
        media.trackList.value = listOf(
            song("1", album = "Album", trackNo = 1, genres = "Rock", folder = "Music/"),
            song("2", album = "Album", trackNo = 2, genres = "Rock", folder = "Music/", status = PackStatus.MISSING),
            whatsapp,
        )
        val mix = playlists.create("Mix", listOf("track:phone:w1", "track:phone:1", "track:phone:2"))
        val vm = vm()
        val ui = vm.ui.filterNotNull().first()
        assertThat(ui.songs.map { it.id }).containsExactly("track:phone:1")
        assertThat(ui.albums.map { it.title to it.trackCount }).containsExactly("Album" to 1)
        assertThat(ui.artists.map { it.name to it.songCount }).containsExactly("Artist" to 1)
        assertThat(ui.genres.map { it.name to it.songCount }).containsExactly("Rock" to 1)
        assertThat(ui.folders.map { it.path to it.songCount }).containsExactly("Music/" to 1)

        assertThat(vm.album(Albums.key("Artist", "Album", "Music/")).first()!!.tracks.map { it.id }).containsExactly("track:phone:1")
        assertThat(vm.album(whatsapp.albumKey).first()).isNull()
        assertThat(vm.artist("WhatsApp").first()).isNull()
        assertThat(vm.genre("Voice").first().songs).isEmpty()
        assertThat(vm.folder("WhatsApp/").first().songs).isEmpty()

        val playlist = vm.playlist(mix).filterNotNull().first()
        assertThat(playlist.tracks.map { it.id }).containsExactly("track:phone:w1", "track:phone:1", "track:phone:2").inOrder()
    }

    /** A MISSING track is never played: a playlist's dimmed songs are left out of the queue. */
    @Test fun missingTracksAreSkippedWhenPlaying() = runTest(main.dispatcher) {
        media.trackList.value = listOf(
            song("1", album = "Album", trackNo = 1),
            song("2", album = "Album", trackNo = 2, status = PackStatus.MISSING),
            song("3", album = "Album", trackNo = 3),
        )
        val mix = playlists.create("Mix", listOf("track:phone:1", "track:phone:2", "track:phone:3"))
        val vm = vm()
        val page = vm.playlist(mix).filterNotNull().first()
        assertThat(page.tracks.map { it.id }).containsExactly("track:phone:1", "track:phone:2", "track:phone:3").inOrder()

        vm.play(page.tracks, tapped = 2, source = MusicSources.playlist(page.playlist))
        assertThat(hub.plays.single()).isEqualTo(
            FakePlayerHub.Play(listOf("track:phone:1", "track:phone:3"), 1, false, PlaySource("playlist:$mix", "Mix")),
        )

        vm.play(page.tracks, tapped = 1, source = MusicSources.playlist(page.playlist)) // the missing one: nothing plays
        assertThat(hub.plays).hasSize(1)

        vm.addToQueue(page.tracks)
        vm.playNext(page.tracks)
        assertThat(hub.queued.single()).containsExactly("track:phone:1", "track:phone:3").inOrder()
        assertThat(hub.nexts.single()).containsExactly("track:phone:1", "track:phone:3").inOrder()

        // Browsing never offers it: the album page plays what it lists.
        val album = vm.album(Albums.key("Artist", "Album", "Music/")).filterNotNull().first()
        vm.play(album.tracks, tapped = 1, source = MusicSources.album(album.album))
        assertThat(hub.plays.last().trackIds).containsExactly("track:phone:1", "track:phone:3").inOrder()
        assertThat(hub.plays.last().startIndex).isEqualTo(1)
    }

    @Test fun albumShuffleAndPlayAllStartAtTheFirstPlayableTrack() = runTest(main.dispatcher) {
        media.trackList.value = listOf(
            song("1", trackNo = 1, status = PackStatus.MISSING),
            song("2", trackNo = 2),
        )
        val vm = vm()
        val page = vm.album(Albums.key("Artist", "Album", "Music/")).filterNotNull().first()
        vm.play(page.tracks, tapped = null, source = MusicSources.album(page.album), shuffle = true)
        assertThat(hub.plays.single().trackIds).containsExactly("track:phone:2")
        assertThat(hub.plays.single().startIndex).isEqualTo(0)
        assertThat(hub.plays.single().shuffle).isTrue()
    }

    @Test fun songsSortByTheChosenKeyUsingSortTags() = runTest(main.dispatcher) {
        media.trackList.value = listOf(
            song("x", title = "The Zebra", date = "1999", addedAt = 3L).copy(sortTitle = "Zebra"),
            song("y", title = "Apple", date = "2005", addedAt = 1L),
            song("z", title = "Mango", date = "1970", addedAt = 2L),
        )
        val vm = vm()
        assertThat(vm.ui.filterNotNull().first().songs.map { it.title }).containsExactly("Apple", "Mango", "The Zebra").inOrder()
        vm.setSort(SongSort.YEAR)
        assertThat(vm.ui.filterNotNull().first { it.sort == SongSort.YEAR }.songs.map { it.title })
            .containsExactly("Mango", "The Zebra", "Apple").inOrder()
        vm.setSort(SongSort.DATE_ADDED)
        assertThat(vm.ui.filterNotNull().first { it.sort == SongSort.DATE_ADDED }.songs.map { it.title })
            .containsExactly("The Zebra", "Mango", "Apple").inOrder()
    }

    @Test fun theSongAndAlbumSortsAndTheAlbumViewAreRemembered() = runTest(main.dispatcher) {
        media.trackList.value = listOf(
            song("1", title = "Zulu", album = "The Wall", albumArtists = "Pink Floyd", date = "1979"),
            song("2", title = "Alpha", album = "Abbey Road", albumArtists = "Beatles", date = "1969"),
        )
        val first = vm()
        val ui = first.ui.filterNotNull().first()
        assertThat(ui.sort).isEqualTo(SongSort.TITLE)
        assertThat(ui.albumSort).isEqualTo(AlbumSort.TITLE)
        assertThat(ui.albums.map { it.title }).containsExactly("Abbey Road", "The Wall").inOrder()
        assertThat(first.albumView.value).isEqualTo(ShelfView.LIST)
        first.setSort(SongSort.YEAR)
        first.setAlbumSort(AlbumSort.YEAR)
        first.toggleAlbumView()
        // Leaving and coming back, or another collection's Music page: the same choices.
        val again = vm("c-any").ui.filterNotNull().first()
        assertThat(again.sort).isEqualTo(SongSort.YEAR)
        assertThat(again.albumSort).isEqualTo(AlbumSort.YEAR)
        assertThat(vm().albumView.value).isEqualTo(ShelfView.GRID)
        assertThat(viewPrefs.sort(ShelfKind.SONGS).value).isEqualTo("YEAR")
        val byYear = vm().ui.filterNotNull().first()
        assertThat(byYear.songs.map { it.title }).containsExactly("Alpha", "Zulu").inOrder()
        assertThat(byYear.albumSections.lettered).isFalse()
    }

    @Test fun moveTargetsAreMusicCollectionsOnly() = runTest(main.dispatcher) {
        val tracks = listOf(song("1"))
        media.trackList.value = tracks
        val books = MediaType("literature", "Literature", true, 0)
        shelves.shelfList.value = listOf(
            Shelf(books, listOf(CollectionRow(ShelfCollection("c-lit", "Lit", "literature", false, "lit"), emptyList()))),
            Shelf(music, listOf(row("c-phone", "On this phone", tracks), row("c-mine", "Mine", emptyList()))),
        )
        val vm = vm()
        backgroundScope.launch { vm.moveTargets.collect {} }
        assertThat(vm.moveTargets.first { it.isNotEmpty() }.map { it.mediaType.id }).containsExactly("music")
        val album = vm.ui.filterNotNull().first().albums.single()
        assertThat(vm.collectionOf(album)).isEqualTo("c-phone")
        vm.moveAlbum(album, "c-mine")
        main.dispatcher.scheduler.advanceUntilIdle()
        assertThat(shelves.moves).containsExactly("album:${album.key}" to "c-mine")
    }

    /**
     * A removed download's track row is gone, so its entry has no row: the rows below it are no longer at their entry
     * ordinals. Moves and removes go by each row's own ordinal.
     */
    @Test fun playlistRowsUseEntryOrdinals() = runTest(main.dispatcher) {
        media.trackList.value = listOf(song("a"), song("c"), song("d"))
        val mix = playlists.create("Mix", listOf("track:phone:a", "track:archive-org:gone", "track:phone:c", "track:phone:d"))
        val vm = vm()
        val page = vm.playlist(mix).filterNotNull().first()
        assertThat(page.items.map { it.ordinal to it.track.id })
            .containsExactly(0 to "track:phone:a", 2 to "track:phone:c", 3 to "track:phone:d").inOrder()
        assertThat(page.tracks.map { it.id }).containsExactly("track:phone:a", "track:phone:c", "track:phone:d").inOrder()

        // "Move up" on c swaps it with a, the row above it.
        vm.moveInPlaylist(mix, page.items[1].ordinal, page.items[0].ordinal)
        advanceUntilIdle()
        assertThat(playlists.entries.value.getValue(mix))
            .containsExactly("track:phone:c", "track:phone:a", "track:archive-org:gone", "track:phone:d").inOrder()

        // "Remove from playlist" on d removes d, not the entry at d's row position.
        val moved = vm.playlist(mix).filterNotNull().first()
        vm.removeFromPlaylist(mix, moved.items[2].ordinal)
        advanceUntilIdle()
        assertThat(playlists.entries.value.getValue(mix))
            .containsExactly("track:phone:c", "track:phone:a", "track:archive-org:gone").inOrder()
    }

    @Test fun downloadedAlbumOffersRemoveDownload() = runTest(main.dispatcher) {
        val id = "holst-the-planets"
        val key = Albums.itemKey("archive-org", id)
        val holst = listOf("mars", "venus").map {
            song(it, album = "The Planets").copy(id = "track:archive-org:$id:$it", origin = MediaOrigin.ARCHIVE_ORG, albumKey = key)
        }
        val phone = listOf(song("p1", album = "Abbey Road"))
        media.trackList.value = holst + phone
        val vm = vm()
        val page = vm.album(key).filterNotNull().first()
        assertThat(page.download).isEqualTo(ItemStatus.Installed)
        page.onRemoveDownload!!()
        assertThat(removed).containsExactly("media:archive-org:$id")

        val phonePage = vm.album(phone.single().albumKey).filterNotNull().first()
        assertThat(phonePage.download).isNull()
        assertThat(phonePage.onRemoveDownload).isNull()
    }
}
