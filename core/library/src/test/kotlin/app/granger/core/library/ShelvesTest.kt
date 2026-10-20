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
class ShelvesTest {
    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    private fun named(id: String, name: String) = pack(id, title = name).copy(name = name)

    private val top = named("u1", "wikipedia_en_top")
    private val medicine = named("m1", "wikipedia_en_medicine")
    private val ifixit = named("i1", "ifixit_en_all")

    private val bookshelf = StubBookshelf()
    private val media = RoomMediaLibrary(db)

    private fun shelves(vararg packs: Pack): Pair<RoomShelves, StubLibrary> {
        val library = StubLibrary(packs.toList())
        return RoomShelves(db, library, bookshelf, media) to library
    }

    private val planets = listOf(
        musicTrack("p1", album = "The Planets", albumArtist = "Holst", trackNo = 1),
        musicTrack("p2", album = "The Planets", albumArtist = "Holst", trackNo = 2),
    )

    /** An audiobook with one file, one chapter. */
    private fun scanned(ref: String, title: String = ref): Pair<ScannedAudiobook, Track> {
        val book = phoneAudiobook(ref, title)
        val file = musicTrack("$ref-1", album = title, albumArtist = "Author", audiobookId = book.id)
        return ScannedAudiobook(book, listOf(file.id), listOf(file.id to ScannedChapter(title, 0L))) to file
    }

    private suspend fun sync(tracks: List<Track>, books: List<Pair<ScannedAudiobook, Track>> = emptyList()) =
        media.sync(MediaOrigin.PHONE, ScanResult(tracks + books.map { it.second }, books.map { it.first }))

    @Test fun packsWithoutAPlacementAreFiledOnFirstRead() = runTest {
        val (s, _) = shelves(top, medicine, ifixit)
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("literature")
        // Kiwix's packs are filed in the Kiwix folder, made on first use.
        val folder = shelf.collections.single()
        assertThat(folder.collection.id).isEqualTo("c:kiwix")
        assertThat(folder.collection.name).isEqualTo("Kiwix")
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.packs).isEmpty()
        assertThat(folder.children.map { it.collection.name }).containsExactly("iFixit", "Wikipedia").inOrder()
        assertThat(folder.children.map { it.packs.size }).containsExactly(1, 2).inOrder()
        assertThat(folder.children.map { it.collection.parentId }).containsExactly("c:kiwix", "c:kiwix")
        // A second read finds nothing more to file.
        val placements = db.shelfDao().getPlacements()
        val collections = db.shelfDao().getCollections()
        assertThat(s.shelves.first().single().allCollections).hasSize(3)
        assertThat(db.shelfDao().getPlacements()).containsExactlyElementsIn(placements)
        assertThat(db.shelfDao().getCollections()).containsExactlyElementsIn(collections)
    }

    @Test fun blankNameFilesUnderOther() = runTest {
        val (s, _) = shelves(pack("x1"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.name).isEqualTo("Other")
        assertThat(row.packs.map { it.id }).containsExactly("x1")
    }

    @Test fun movedPackKeepsItsPlaceAfterUpdate() = runTest {
        val (s, library) = shelves(top)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.movePack(top, mine)
        library.state.value = listOf(named("u2", "wikipedia_en_top"))
        val shelf = s.shelves.first().single()
        val row = shelf.collections.single()
        assertThat(row.collection.id).isEqualTo(mine)
        assertThat(row.packs.map { it.id }).containsExactly("u2")
        assertThat(s.collection(mine).first()!!.packs.map { it.id }).containsExactly("u2")
    }

    @Test fun moveCollectionToNewMediaType() = runTest {
        val (s, _) = shelves(top, ifixit)
        val wikipedia = s.shelves.first().single().allCollections.first { it.collection.name == "Wikipedia" }.collection.id
        val reference = s.createMediaType("Reference")
        s.moveCollection(wikipedia, reference)
        val shelves = s.shelves.first()
        assertThat(shelves.map { it.mediaType.name }).containsExactly("Literature", "Reference").inOrder()
        // Moving a child out of its media type takes it out of the folder too.
        val moved = shelves[1].collections.single()
        assertThat(moved.collection.name).isEqualTo("Wikipedia")
        assertThat(moved.collection.parentId).isNull()
        assertThat(shelves[0].collections.single().children.map { it.collection.name }).containsExactly("iFixit")
        // Moved back, it stays at the top of Literature: the user put it there.
        s.moveCollection(wikipedia, "literature")
        assertThat(s.shelves.first().single().collections.map { it.collection.name }).containsExactly("Kiwix", "Wikipedia").inOrder()
    }

    @Test fun aFolderWhoseChildrenAreAllEmptyIsHidden() = runTest {
        val (s, library) = shelves(top)
        assertThat(s.shelves.first().single().collections.single().isFolder).isTrue()
        val mine = s.createCollection("Mine", "literature")
        s.movePack(top, mine)
        // The folder and Wikipedia still exist, empty; the Library shows only Mine.
        assertThat(s.shelves.first().single().collections.map { it.collection.name }).containsExactly("Mine")
        assertThat(s.collection("c:kiwix").first()!!.children).isEmpty()
        library.state.value = listOf(top, medicine)
        // A new Wikipedia pack is filed in the old Wikipedia collection, back in its folder.
        val folder = s.shelves.first().single().collections.first { it.isFolder }
        assertThat(folder.children.single().packs.map { it.id }).containsExactly("m1")
    }

    @Test fun theFolderScreenGetsTheFolderWithItsChildren() = runTest {
        val (s, _) = shelves(top, ifixit)
        s.shelves.first()
        val folder = s.collection("c:kiwix").first()!!
        assertThat(folder.children.map { it.collection.name }).containsExactly("iFixit", "Wikipedia").inOrder()
        val child = s.collection("c:wikipedia").first()!!
        assertThat(child.packs.map { it.id }).containsExactly("u1")
        assertThat(child.children).isEmpty()
    }

    @Test fun kiwixPacksGoInTheFolderAndGrangerAndNamelessOnesDont() = runTest {
        val devdocs = named("d1", "devdocs_en_python")
        val zimgit = named("z2", "zimgit-medicine_en")
        val (s, _) = shelves(named("z1", "granger_classics"), pack("x1"), devdocs, zimgit)
        val shelf = s.shelves.first().single()
        assertThat(shelf.collections.map { it.collection.name }).containsExactly("Granger Classics", "Kiwix", "Other").inOrder()
        assertThat(shelf.collections.first { it.isFolder }.children.map { it.collection.defaultKey })
            .containsExactly("devdocs", "pack:zimgit-medicine_en")
        // A user collection is never put in the folder.
        val mine = s.createCollection("Mine", "literature")
        s.movePack(devdocs, mine)
        assertThat(db.shelfDao().collection(mine)!!.parentId).isNull()
    }

    @Test fun mapsPacksFileUnderMapsAsOpenStreetMap() = runTest {
        val (s, _) = shelves(named("h1", "maps_en_haiti"), top)
        val shelves = s.shelves.first()
        assertThat(shelves.map { it.mediaType.id }).containsExactly("literature", "maps").inOrder()
        assertThat(shelves[1].mediaType.name).isEqualTo("Maps")
        val maps = shelves[1].collections.single()
        assertThat(maps.collection.id).isEqualTo("c:maps")
        assertThat(maps.collection.name).isEqualTo("OpenStreetMap")
        assertThat(maps.collection.parentId).isNull()
        assertThat(maps.packs.map { it.id }).containsExactly("h1")
    }

    @Test fun builtInMediaTypeCannotBeDeleted() = runTest {
        val (s, _) = shelves()
        assertThat(s.deleteMediaType("music")).isFalse()
        assertThat(db.shelfDao().getMediaTypes().map { it.id }).contains("music")
    }

    @Test fun nonEmptyUserMediaTypeCannotBeDeleted() = runTest {
        val (s, _) = shelves(top)
        val wikipedia = s.shelves.first().single().collections.single().children.single().collection.id
        val reference = s.createMediaType("Reference")
        s.moveCollection(wikipedia, reference)
        assertThat(s.deleteMediaType(reference)).isFalse()
        val empty = s.createMediaType("Empty")
        assertThat(s.deleteMediaType(empty)).isTrue()
    }

    @Test fun deletingACollectionRefilesItsPacks() = runTest {
        val (s, _) = shelves(top)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.movePack(top, mine)
        s.deleteCollection(mine)
        val row = s.shelves.first().single().collections.single().children.single()
        assertThat(row.collection.name).isEqualTo("Wikipedia")
        assertThat(row.packs.map { it.id }).containsExactly("u1")
        assertThat(s.collection(mine).first()).isNull()
    }

    @Test fun emptyMediaTypesAreHidden() = runTest {
        val (s, _) = shelves(top)
        assertThat(s.shelves.first().map { it.mediaType.id }).containsExactly("literature")
    }

    @Test fun booksAndPacksShareACollection() = runTest {
        val (s, _) = shelves(named("z1", "granger_classics_en"))
        bookshelf.state.value = listOf(book("pride"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(row.collection.name).isEqualTo("Granger Classics")
        assertThat(row.packs.map { it.id }).containsExactly("z1")
        assertThat(row.books.map { it.id }).containsExactly(BookIds.of(BookOrigin.GRANGER, "pride"))
    }

    @Test fun importsFileUnderMyBooks() = runTest {
        val (s, _) = shelves()
        bookshelf.state.value = listOf(book("abc", BookOrigin.IMPORT), book("ia1", BookOrigin.ARCHIVE_ORG))
        // A shelf holding only books is shown.
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("literature")
        assertThat(shelf.collections.map { it.collection.name }).containsExactly("Archive.org", "My books").inOrder()
        val mine = shelf.collections.first { it.collection.defaultKey == "my-books" }
        assertThat(mine.books.map { it.sourceRef }).containsExactly("abc")
        assertThat(mine.packs).isEmpty()
    }

    @Test fun movedBookKeepsItsPlace() = runTest {
        val (s, _) = shelves()
        val pride = book("pride")
        bookshelf.state.value = listOf(pride)
        s.shelves.first()
        val mine = s.createCollection("Mine", "literature")
        s.moveBook(pride, mine)
        // A re-download changes the file but not the id.
        bookshelf.state.value = listOf(pride.copy(filePath = "/books/pride_2026-11-01.epub", title = "Pride (revised)"))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.id).isEqualTo(mine)
        assertThat(row.books.map { it.title }).containsExactly("Pride (revised)")
        assertThat(s.collection(mine).first()!!.books.map { it.id }).containsExactly(pride.id)
        // A collection holding a book isn't empty, so its media type can't be deleted.
        val reference = s.createMediaType("Reference")
        s.moveCollection(mine, reference)
        assertThat(s.deleteMediaType(reference)).isFalse()
        // Deleting the collection files the book back by origin.
        s.deleteCollection(mine)
        val back = s.shelves.first().single().collections.single()
        assertThat(back.collection.defaultKey).isEqualTo("granger-classics")
        assertThat(back.books.map { it.id }).containsExactly(pride.id)
    }

    @Test fun filingRules() {
        assertThat(Filing.defaultKey(named("a", "wikipedia_en_medicine"))).isEqualTo("wikipedia")
        assertThat(Filing.defaultKey(named("a", "granger_classics"))).isEqualTo("granger-classics")
        assertThat(Filing.defaultKey(named("a", "askubuntu.com_en_stackexchange"))).isEqualTo("stack-exchange")
        assertThat(Filing.defaultKey(named("a", "superuser.com_en_all"))).isEqualTo("stack-exchange")
        assertThat(Filing.defaultKey(named("a", "tezos.stackexchange.com_en_all"))).isEqualTo("stack-exchange")
        assertThat(Filing.defaultKey(named("a", "wikivoyage_en_all"))).isEqualTo("wikivoyage")
        assertThat(Filing.defaultKey(named("a", "wikibooks_en_all"))).isEqualTo("wikibooks")
        assertThat(Filing.defaultKey(named("a", "wikiquote_en_all"))).isEqualTo("wikiquote")
        assertThat(Filing.defaultKey(named("a", "wikiversity_en_all"))).isEqualTo("wikiversity")
        assertThat(Filing.defaultKey(named("a", "phet_en_all"))).isEqualTo("phet")
        assertThat(Filing.defaultKey(named("a", "maps_en_fiji"))).isEqualTo("maps")
        assertThat(Filing.defaultKey(named("a", "devdocs_en_python"))).isEqualTo("devdocs")
        assertThat(Filing.defaultKey(named("a", "wikipedia_en_physics"))).isEqualTo("wikipedia")
        assertThat(Filing.defaultKey(named("a", "zimgit"))).isEqualTo("pack:zimgit")
        assertThat(Filing.packKey(pack("a"))).isEqualTo("a")
        assertThat(Filing.bookKey(book("x"))).isEqualTo("book:granger:x")
        assertThat(Filing.defaultKey(book("x", BookOrigin.IMPORT))).isEqualTo("my-books")
        assertThat(Filing.defaultKey(book("x", BookOrigin.ARCHIVE_ORG))).isEqualTo("archive-org")
        assertThat(Filing.isSuperseded(named("a", "granger_classics_en"))).isTrue()
        assertThat(Filing.isSuperseded(named("a", "gutenberg_en_all"))).isFalse()
        assertThat(Filing.defaultMediaType(named("a", "maps_en_fiji"))).isEqualTo("maps")
        assertThat(Filing.defaultMediaType(named("a", "wikipedia_en_top"))).isEqualTo("literature")
        assertThat(Filing.defaultName("maps", named("a", "maps_en_fiji"))).isEqualTo("OpenStreetMap")
        assertThat(Filing.isKiwix(named("a", "wikipedia_en_top"))).isTrue()
        assertThat(Filing.isKiwix(named("a", "granger_classics_en"))).isFalse()
        assertThat(Filing.isKiwix(pack("a"))).isFalse()
        assertThat(Filing.folderKey(named("a", "devdocs_en_python"))).isEqualTo("kiwix")
        assertThat(Filing.folderKey(named("a", "maps_en_fiji"))).isNull()
        assertThat(Filing.folderKey(named("a", "granger_classics"))).isNull()
    }

    @Test fun phoneAlbumsFileUnderMusicOnThisPhone() = runTest {
        val (s, _) = shelves()
        sync(planets + musicTrack("b1", album = "Goldberg Variations", albumArtist = "Bach"))
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("music")
        val row = shelf.collections.single()
        assertThat(row.collection.name).isEqualTo("On this phone")
        assertThat(row.collection.userCreated).isFalse()
        assertThat(row.albums.map { it.title }).containsExactly("Goldberg Variations", "The Planets").inOrder()
        assertThat(row.albums.map { it.trackCount }).containsExactly(1, 2).inOrder()
        assertThat(row.packs).isEmpty()
        assertThat(db.shelfDao().getPlacements().map { it.packKey })
            .containsExactly("album:bach|goldberg variations", "album:holst|the planets")
    }

    @Test fun phoneAudiobooksFileUnderAudiobooksOnThisPhone() = runTest {
        val (s, _) = shelves()
        sync(planets, listOf(scanned("hobbit", "The Hobbit")))
        val shelves = s.shelves.first()
        assertThat(shelves.map { it.mediaType.id }).containsExactly("audiobooks", "music").inOrder()
        val books = shelves[0].collections.single()
        assertThat(books.collection.name).isEqualTo("On this phone")
        assertThat(books.audiobooks.map { it.title }).containsExactly("The Hobbit")
        // An audiobook's own files aren't music.
        assertThat(books.albums).isEmpty()
        val music = shelves[1].collections.single()
        assertThat(music.collection.name).isEqualTo("On this phone")
        assertThat(music.albums.map { it.title }).containsExactly("The Planets")
        assertThat(music.collection.id).isNotEqualTo(books.collection.id)
        assertThat(db.shelfDao().getPlacements().map { it.packKey }).contains("audiobook:phone:hobbit")
    }

    @Test fun movedAlbumStaysMovedAfterResync() = runTest {
        val (s, _) = shelves()
        sync(planets)
        val album = s.shelves.first().single().collections.single().albums.single()
        val classical = s.createCollection("Classical", "music")
        s.moveAlbum(album, classical)
        // A re-scan retitles one track and finds a third.
        sync(listOf(planets[0].copy(title = "Mars"), planets[1], musicTrack("p3", album = "The Planets", albumArtist = "Holst", trackNo = 3)))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.id).isEqualTo(classical)
        assertThat(row.albums.single().trackCount).isEqualTo(3)
        assertThat(s.collection(classical).first()!!.albums.map { it.key }).containsExactly(album.key)
        assertThat(db.shelfDao().getPlacements().single { it.packKey == Filing.albumKey(album) }.userSet).isTrue()
    }

    @Test fun movedAudiobookStaysMovedAfterResync() = runTest {
        val (s, _) = shelves()
        val hobbit = scanned("hobbit", "The Hobbit")
        sync(emptyList(), listOf(hobbit))
        val book = s.shelves.first().single().collections.single().audiobooks.single()
        val tolkien = s.createCollection("Tolkien", "audiobooks")
        s.moveAudiobook(book, tolkien)
        sync(emptyList(), listOf(hobbit.first.copy(book = book.copy(narrator = "Andy Serkis")) to hobbit.second))
        val row = s.shelves.first().single().collections.single()
        assertThat(row.collection.id).isEqualTo(tolkien)
        assertThat(row.audiobooks.single().narrator).isEqualTo("Andy Serkis")
    }

    @Test fun musicShelfHiddenWhenNoTracks() = runTest {
        val (s, _) = shelves(top)
        assertThat(s.shelves.first().map { it.mediaType.id }).containsExactly("literature")
        // Audiobook files alone don't make a Music shelf.
        sync(emptyList(), listOf(scanned("hobbit")))
        assertThat(s.shelves.first().map { it.mediaType.id }).containsExactly("literature", "audiobooks").inOrder()
    }

    /** Music browsing hides MISSING tracks, so the Music shelf does too: counts are of present tracks only. */
    @Test fun missingTracksLeaveTheMusicShelf() = runTest {
        val (s, _) = shelves(top)
        val gone = musicTrack("w1", album = "WhatsApp Audio", albumArtist = "")
        sync(planets + gone)
        assertThat(s.shelves.first().single { it.mediaType.id == "music" }.collections.single().albums).hasSize(2)

        // A rescan finds the WhatsApp file and one of the Planets gone.
        sync(listOf(planets[0]))
        val albums = s.shelves.first().single { it.mediaType.id == "music" }.collections.single().albums
        assertThat(albums.map { it.title }).containsExactly("The Planets")
        assertThat(albums.single().trackCount).isEqualTo(1)

        // Every track gone: no Music shelf at all (the rows are still in the db).
        sync(emptyList())
        assertThat(s.shelves.first().map { it.mediaType.id }).containsExactly("literature")
        assertThat(db.mediaDao().tracksOf(MediaOrigin.PHONE)).hasSize(3)
    }

    /** A downloaded album: every track keyed by its origin item, as the downloaded-media scanner files them. */
    private fun itemAlbum(identifier: String, title: String, count: Int = 2) = (1..count).map { n ->
        musicTrack("$identifier-$n", album = title, albumArtist = "Gustav Holst", folder = "music/archive-org/$identifier/", trackNo = n)
            .copy(id = "track:archive-org:$identifier:$n", origin = MediaOrigin.ARCHIVE_ORG, uri = "file:///music/$identifier/$n.mp3",
                albumKey = Albums.itemKey("archive-org", identifier))
    }

    /** A downloaded audiobook of [origin] with one file. */
    private fun downloadedBook(origin: MediaOrigin, ref: String, title: String): Pair<ScannedAudiobook, Track> {
        val source = if (origin == MediaOrigin.LIBRIVOX) "librivox" else "archive-org"
        val book = phoneAudiobook(ref, title).copy(id = "audiobook:$source:$ref", origin = origin)
        val file = musicTrack("$ref-1", album = title, albumArtist = "Author", audiobookId = book.id)
            .copy(id = "track:$source:$ref:1", origin = origin, uri = "file:///audiobooks/$ref/1.mp3")
        return ScannedAudiobook(book, listOf(file.id), listOf(file.id to ScannedChapter(title, 0L))) to file
    }

    private suspend fun syncDownloads(origin: MediaOrigin, tracks: List<Track>, books: List<Pair<ScannedAudiobook, Track>> = emptyList()) =
        media.sync(origin, ScanResult(tracks + books.map { it.second }, books.map { it.first }))

    @Test fun librivoxBooksFileUnderAudiobooksLibriVox() = runTest {
        val (s, _) = shelves()
        syncDownloads(MediaOrigin.LIBRIVOX, emptyList(), listOf(downloadedBook(MediaOrigin.LIBRIVOX, "817", "Time Machine")))
        val shelf = s.shelves.first().single()
        assertThat(shelf.mediaType.id).isEqualTo("audiobooks")
        val row = shelf.collections.single()
        assertThat(row.collection.name).isEqualTo("LibriVox")
        assertThat(row.collection.defaultKey).isEqualTo("audiobooks:librivox")
        assertThat(row.audiobooks.map { it.id }).containsExactly("audiobook:librivox:817")
        assertThat(row.albums).isEmpty()
    }

    @Test fun archiveAlbumsFileUnderMusicArchiveOrgAndSpokenWordUnderAudiobooks() = runTest {
        val (s, _) = shelves()
        syncDownloads(MediaOrigin.ARCHIVE_ORG, itemAlbum("holst", "The Planets", count = 3),
            listOf(downloadedBook(MediaOrigin.ARCHIVE_ORG, "odyssey_reading", "The Odyssey")))
        val shelves = s.shelves.first()
        assertThat(shelves.map { it.mediaType.id }).containsExactly("audiobooks", "music").inOrder()
        val books = shelves[0].collections.single()
        assertThat(books.collection.name).isEqualTo("Archive.org")
        assertThat(books.collection.defaultKey).isEqualTo("audiobooks:archive-org")
        assertThat(books.audiobooks.map { it.title }).containsExactly("The Odyssey")
        val music = shelves[1].collections.single()
        assertThat(music.collection.name).isEqualTo("Archive.org")
        assertThat(music.collection.defaultKey).isEqualTo("music:archive-org")
        assertThat(music.albums.map { it.title to it.trackCount }).containsExactly("The Planets" to 3)
        assertThat(music.albums.single().key).isEqualTo(Albums.itemKey("archive-org", "holst"))
    }

    @Test fun archiveBooksAndArchiveAudioGetSeparateCollections() = runTest {
        val (s, _) = shelves()
        bookshelf.state.value = listOf(book("ia1", BookOrigin.ARCHIVE_ORG))
        syncDownloads(MediaOrigin.ARCHIVE_ORG, itemAlbum("holst", "The Planets"),
            listOf(downloadedBook(MediaOrigin.ARCHIVE_ORG, "odyssey_reading", "The Odyssey")))
        val rows = s.shelves.first().flatMap { shelf -> shelf.collections.map { shelf.mediaType.id to it } }
        assertThat(rows.map { (type, row) -> type to row.collection.defaultKey }).containsExactly(
            "literature" to "archive-org", "audiobooks" to "audiobooks:archive-org", "music" to "music:archive-org",
        )
        assertThat(rows.map { it.second.collection.id }.toSet()).hasSize(3)
        assertThat(rows.map { it.second.collection.name }.toSet()).containsExactly("Archive.org")
        val literature = rows.single { it.first == "literature" }.second
        assertThat(literature.books.map { it.sourceRef }).containsExactly("ia1")
        assertThat(literature.albums).isEmpty()
        assertThat(literature.audiobooks).isEmpty()
    }

    /** Remove forgets the rows but not the user's placement: getting it again puts it back where they moved it. */
    @Test fun movedDownloadedAlbumStaysMovedAfterRemoveAndGetAgain() = runTest {
        val (s, _) = shelves()
        val holst = itemAlbum("holst", "The Planets")
        val odyssey = downloadedBook(MediaOrigin.ARCHIVE_ORG, "odyssey_reading", "The Odyssey")
        syncDownloads(MediaOrigin.ARCHIVE_ORG, holst, listOf(odyssey))
        val shelves = s.shelves.first()
        val album = shelves.single { it.mediaType.id == "music" }.collections.single().albums.single()
        val book = shelves.single { it.mediaType.id == "audiobooks" }.collections.single().audiobooks.single()
        val classical = s.createCollection("Classical", "music")
        val homer = s.createCollection("Homer", "audiobooks")
        s.moveAlbum(album, classical)
        s.moveAudiobook(book, homer)

        media.forget(holst.map { it.id } + odyssey.second.id, listOf(odyssey.first.book.id))
        // No ghost: nothing is left on either shelf, though the placements stay.
        assertThat(s.shelves.first().flatMap { it.collections }.flatMap { it.albums + it.audiobooks }).isEmpty()
        assertThat(db.shelfDao().getPlacements().filter { it.userSet }.map { it.packKey })
            .containsExactly(Filing.albumKey(album), Filing.audiobookKey(book))

        syncDownloads(MediaOrigin.ARCHIVE_ORG, holst, listOf(odyssey))
        assertThat(s.collection(classical).first()!!.albums.map { it.key }).containsExactly(album.key)
        assertThat(s.collection(homer).first()!!.audiobooks.map { it.id }).containsExactly(book.id)
        val after = s.shelves.first()
        assertThat(after.single { it.mediaType.id == "music" }.collections.map { it.collection.id }).containsExactly(classical)
        assertThat(after.single { it.mediaType.id == "audiobooks" }.collections.map { it.collection.id }).containsExactly(homer)
    }
}
