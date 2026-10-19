package app.granger.core.media

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.AudiobookState
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaFolder
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.Playlist
import app.granger.core.library.PlaylistEntry
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.taglib.RawTags
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val media = RoomMediaLibrary(db)
    private val playback = db.playbackDao()
    private val mediaDao = db.mediaDao()

    /** What each uri holds on "disk"; a uri missing here opens as null, as a resolver does for a vanished row. */
    private val disk = mutableMapOf<String, File>()
    private val failing = mutableSetOf<String>()
    private val opened = mutableListOf<String>()
    private val modes = mutableListOf<String>()
    /** Tags by display name; a file not listed gets a TITLE from its name. */
    private val tags = mutableMapOf<String, FileTags>()
    private var permission = true

    /** The production opener, with only the resolver call faked: every mode it asks for is recorded. */
    private val opener = ContentResolverOpener { uri, mode ->
        modes += mode
        opened += uri.toString()
        if (uri.toString() in failing) throw FileNotFoundException(uri.toString())
        disk[uri.toString()]?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.parseMode(mode)) }
    }

    private val tagReader = TagReader { _, f -> tags[f.displayName] ?: FileTags(raw(f.displayName.substringBeforeLast('.')), emptyList()) }

    private val coversDir by lazy { tmp.newFolder("covers") }

    @After fun close() = db.close()

    /** Owns the uris under [prefix]. */
    private class FakeSource(var files: List<FoundFile>, private val prefix: String = "content://media/") : AudioSource {
        override fun owns(uri: String) = uri.startsWith(prefix)
        var calls = 0
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun list(): List<FoundFile> {
            calls++
            gate?.await()
            error?.let { throw it }
            return files
        }
    }

    private fun scanner(vararg sources: AudioSource) =
        PhoneScanner(sources.toList(), opener, media, playback, coversDir, { permission }, tagReader)

    private var nextSeed = 1

    /** A file of random bytes behind [uri]. */
    private fun found(
        uri: String,
        name: String,
        folder: String = "Music/",
        bytes: ByteArray = Random(nextSeed++).nextBytes(100_000),
        durationMs: Long = 60_000L,
        inAudiobookFolder: Boolean = false,
        folderCoverUri: String? = null,
    ): FoundFile {
        disk[uri] = tmp.newFile().apply { writeBytes(bytes) }
        return FoundFile(uri, name, folder, bytes.size.toLong(), 1_000L, durationMs, false, inAudiobookFolder, folderCoverUri)
    }

    private fun fingerprintOf(uri: String) = FileInputStream(disk.getValue(uri)).use { Fingerprint.of(it.channel) }

    private fun raw(title: String, album: String = "", artist: String = "", track: Int? = null, cover: ByteArray? = null) = RawTags(
        properties = buildMap {
            put("TITLE", listOf(title))
            if (album.isNotEmpty()) put("ALBUM", listOf(album))
            if (artist.isNotEmpty()) put("ARTIST", listOf(artist))
            if (track != null) put("TRACKNUMBER", listOf("$track"))
        },
        durationMs = 60_000L, sampleRate = 44_100, bitrateKbps = 128, channels = 2, cover = cover,
    )

    private suspend fun phoneTracks() = mediaDao.tracksOf(MediaOrigin.PHONE).sortedBy { it.id }
    private suspend fun phoneBooks() = mediaDao.audiobooksOf(MediaOrigin.PHONE).sortedBy { it.id }

    private fun state(audiobookId: String, trackId: String) =
        AudiobookState(audiobookId, trackId, positionMs = 123_456L, speed = 1.5f, skipSilence = true, volumeBoostMb = 0, finished = false, updatedAt = 9L)

    @Test fun movedFileKeepsIdAndPosition() = runTest {
        val bytes = Random(42).nextBytes(200_000)
        val chapters = listOf(Chapter("One", 0), Chapter("Two", 20_000), Chapter("Three", 40_000))
        tags["Dune.m4b"] = FileTags(raw("Dune", album = "Dune", artist = "Frank Herbert"), chapters)
        val before = found("content://media/external/audio/media/1", "Dune.m4b", "Audiobooks/", bytes)
        val source = FakeSource(listOf(before))
        val scanner = scanner(source)
        scanner.scan()

        val book = phoneBooks().single()
        val track = phoneTracks().single()
        assertThat(track.id).isEqualTo("track:phone:${fingerprintOf(before.uri)}")
        assertThat(book.id).isEqualTo("audiobook:phone:${fingerprintOf(before.uri)}")
        playback.upsertAudiobookState(state(book.id, track.id))

        // The user moves the file: MediaStore gives it a new row (new uri) in a new folder, same bytes.
        disk.remove(before.uri)
        source.files = listOf(found("content://media/external/audio/media/77", "Dune.m4b", "Audiobooks/Herbert/", bytes))
        assertThat(scanner.scan()).isEqualTo(1)

        val moved = phoneTracks().single()
        assertThat(moved.id).isEqualTo(track.id)
        assertThat(moved.uri).isEqualTo("content://media/external/audio/media/77")
        assertThat(moved.folder).isEqualTo("Audiobooks/Herbert/")
        assertThat(moved.status).isEqualTo(PackStatus.OK)
        assertThat(moved.audiobookId).isEqualTo(book.id)
        assertThat(phoneBooks().map { it.id to it.status }).containsExactly(book.id to PackStatus.OK)
        assertThat(media.chapters(book.id).first().map { it.title }).containsExactly("One", "Two", "Three").inOrder()
        assertThat(playback.audiobookState(book.id)).isEqualTo(state(book.id, track.id))
    }

    @Test fun unchangedFileIsNotReopened() = runTest {
        tags["Dune.m4b"] = FileTags(raw("Dune", album = "Dune"), listOf(Chapter("One", 0), Chapter("Two", 30_000)))
        val source = FakeSource(listOf(
            found("content://media/1", "Mars.mp3", "Music/Holst/"),
            found("content://media/2", "Dune.m4b", "Audiobooks/"),
            found("content://media/3", "1.mp3", "Audiobooks/Emma/"),
            found("content://media/4", "2.mp3", "Audiobooks/Emma/"),
        ))
        val scanner = scanner(source)
        assertThat(scanner.scan()).isEqualTo(4)
        val tracks = phoneTracks()
        val books = phoneBooks()
        val chapters = books.associate { it.id to media.chapters(it.id).first() }
        opened.clear()

        assertThat(scanner.scan()).isEqualTo(0)
        assertThat(opened).isEmpty()
        // An unchanged library re-syncs to exactly the rows it had.
        assertThat(phoneTracks()).isEqualTo(tracks)
        assertThat(phoneBooks()).isEqualTo(books)
        assertThat(books.associate { it.id to media.chapters(it.id).first() }).isEqualTo(chapters)

        // A changed file (new size or date) is read again.
        source.files = source.files.map { if (it.displayName == "Mars.mp3") it.copy(modifiedMs = 2_000L) else it }
        assertThat(scanner.scan()).isEqualTo(1)
        assertThat(opened).containsExactly("content://media/1")
    }

    @Test fun unreadableFileIsSkippedAndScanContinues() = runTest {
        val a = found("content://media/1", "a.mp3")
        val b = found("content://media/2", "b.mp3").also { failing += it.uri }
        val c = found("content://media/3", "c.mp3").also { disk.remove(it.uri) } // opens as null
        val d = found("content://media/4", "d.mp3")
        assertThat(scanner(FakeSource(listOf(a, b, c, d))).scan()).isEqualTo(2)
        assertThat(phoneTracks().map { it.title }).containsExactly("a", "d")
    }

    @Test fun fileWithBrokenTagsIsTitledFromItsFileName() = runTest {
        tags["03 - Broken.mp3"] = FileTags(raw = null, chapters = emptyList()) // TagLib couldn't parse it
        val f = found("content://media/1", "03 - Broken.mp3", "Music/Odd/", durationMs = 42_000L)
        assertThat(scanner(FakeSource(listOf(f))).scan()).isEqualTo(1)
        val track = phoneTracks().single()
        assertThat(track.title).isEqualTo("03 - Broken")
        assertThat(track.album).isEmpty()
        assertThat(track.durationMs).isEqualTo(42_000L)
        assertThat(track.albumKey).isEqualTo("|unknown|Music/Odd/")
    }

    @Test fun tagReaderFailureSkipsOnlyThatFile() = runTest {
        val good = found("content://media/1", "good.mp3")
        val bad = found("content://media/2", "bad.mp3")
        val reader = TagReader { pfd, f -> if (f == bad) throw IOException("read error") else tagReader.read(pfd, f) }
        val scanner = PhoneScanner(listOf(FakeSource(listOf(good, bad))), opener, media, playback, coversDir, { true }, reader)
        assertThat(scanner.scan()).isEqualTo(1)
        assertThat(phoneTracks().map { it.title }).containsExactly("good")
    }

    @Test fun permissionLostMarksPhoneItemsMissingAndKeepsState() = runTest {
        tags["Dune.m4b"] = FileTags(raw("Dune", album = "Dune"), listOf(Chapter("One", 0), Chapter("Two", 30_000)))
        val source = FakeSource(listOf(found("content://media/1", "Mars.mp3"), found("content://media/2", "Dune.m4b", "Audiobooks/")))
        val scanner = scanner(source)
        scanner.scan()
        val book = phoneBooks().single()
        val bookTrack = phoneTracks().single { it.audiobookId == book.id }
        val song = phoneTracks().single { it.audiobookId == null }
        playback.upsertAudiobookState(state(book.id, bookTrack.id))
        val playlistId = playback.insertPlaylist(Playlist(0, "Mix", 1L, 1L))
        playback.insertEntries(listOf(PlaylistEntry(playlistId, 0, song.id)))

        permission = false
        source.calls = 0
        opened.clear()
        assertThat(scanner.scan()).isEqualTo(0)

        assertThat(source.calls).isEqualTo(0)
        assertThat(opened).isEmpty()
        assertThat(phoneTracks().map { it.status }.distinct()).containsExactly(PackStatus.MISSING)
        assertThat(phoneBooks().map { it.status }).containsExactly(PackStatus.MISSING)
        assertThat(phoneTracks().map { it.id }).containsExactly(song.id, bookTrack.id)
        assertThat(playback.audiobookState(book.id)).isEqualTo(state(book.id, bookTrack.id))
        assertThat(playback.entries(playlistId).map { it.trackId }).containsExactly(song.id)
        assertThat(media.chapters(book.id).first()).hasSize(2)

        // Permission granted again: everything comes back OK, from the cache, without reopening.
        permission = true
        scanner.scan()
        assertThat(phoneTracks().map { it.status }.distinct()).containsExactly(PackStatus.OK)
        assertThat(phoneBooks().map { it.status }).containsExactly(PackStatus.OK)
        assertThat(opened).isEmpty()
    }

    /** SAF trees in memory: each tree holds files directly under its root. */
    private class FakeTree : DocumentTree {
        val granted = mutableSetOf<Uri>()
        /** A tree missing here is gone: the provider says its top folder no longer exists. */
        val roots = mutableMapOf<Uri, DocumentTree.Document>()
        val children = mutableMapOf<String, List<DocumentTree.Document>>()
        /** Folders whose provider fails when asked for their children. */
        val failing = mutableSetOf<String>()
        override fun isGranted(treeUri: Uri) = treeUri in granted
        override fun root(treeUri: Uri) = roots[treeUri]
        override fun children(treeUri: Uri, documentId: String): List<DocumentTree.Document> {
            if (documentId in failing) throw IOException("provider died")
            return children[documentId].orEmpty()
        }
    }

    private val authority = "com.android.externalstorage.documents"

    /** Adds a granted tree [rootId] named [name] holding [files], and returns its uri. */
    private fun FakeTree.tree(rootId: String, name: String, files: List<String>): Uri {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, rootId)
        granted += tree
        roots[tree] = DocumentTree.Document(rootId, name, DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L)
        children[rootId] = emptyList()
        addFiles(tree, rootId, files)
        return tree
    }

    private fun FakeTree.addFiles(tree: Uri, rootId: String, files: List<String>) {
        children[rootId] = children[rootId].orEmpty() + files.map { file ->
            val id = "$rootId/$file"
            val bytes = Random(nextSeed++).nextBytes(50_000)
            disk[DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()] = tmp.newFile().apply { writeBytes(bytes) }
            DocumentTree.Document(id, file, if (file.endsWith(".jpg")) "image/jpeg" else "audio/mpeg", bytes.size.toLong(), 5_000L)
        }
    }

    @Test fun folderGrantLostMarksOnlyThatFoldersItemsMissing() = runTest {
        val fakeTree = FakeTree()
        val books = fakeTree.tree("primary:Stories", "Stories", listOf("1.mp3", "2.mp3", "cover.jpg"))
        val music = fakeTree.tree("primary:Tapes", "Tapes", listOf("side-a.mp3"))
        val folders = listOf(MediaFolder(books.toString(), audiobooks = true, addedAt = 1L), MediaFolder(music.toString(), audiobooks = false, addedAt = 2L))
        val folderSource = FolderSource(ApplicationProvider.getApplicationContext(), { folders }, fakeTree)
        val store = FakeSource(listOf(found("content://media/1", "Mars.mp3")))
        val scanner = scanner(store, folderSource)
        assertThat(scanner.scan()).isEqualTo(4)

        val book = phoneBooks().single()
        assertThat(phoneTracks().filter { it.audiobookId == book.id }.map { it.title }).containsExactly("1", "2")
        assertThat(phoneTracks().first { it.title == "1" }.folder).isEqualTo("Stories/")
        assertThat(book.coverPath).isNotNull() // cover.jpg beside the files

        fakeTree.granted -= music // the user revoked the Tapes folder, or its volume is gone
        scanner.scan()
        assertThat(phoneTracks().associate { it.title to it.status }).containsExactly(
            "Mars", PackStatus.OK, "1", PackStatus.OK, "2", PackStatus.OK, "side-a", PackStatus.MISSING,
        )
        assertThat(phoneBooks().single().status).isEqualTo(PackStatus.OK)
    }

    @Test fun failingSourceKeepsItsItemsWhileOthersSync() = runTest {
        val store = FakeSource(listOf(found("content://media/1", "Mars.mp3")))
        val other = FakeSource(listOf(found("content://other/1", "Venus.mp3")), prefix = "content://other/")
        val scanner = scanner(store, other)
        scanner.scan()
        val venus = phoneTracks().single { it.title == "Venus" }

        // The other source fails: unknown, not empty. Its item stays exactly as it was; the store syncs as usual.
        other.error = IOException("provider died")
        store.files = listOf(found("content://media/2", "Jupiter.mp3"))
        assertThat(scanner.scan()).isEqualTo(1)
        assertThat(phoneTracks().single { it.title == "Venus" }).isEqualTo(venus)
        assertThat(phoneTracks().associate { it.title to it.status }).containsExactly(
            "Mars", PackStatus.MISSING, "Jupiter", PackStatus.OK, "Venus", PackStatus.OK,
        )
        // Its cache entry is kept too, so it isn't reopened when the source comes back.
        assertThat(playback.scanCacheUris()).containsExactly("content://media/2", "content://other/1")
        other.error = null
        opened.clear()
        scanner.scan()
        assertThat(opened).isEmpty()
    }

    @Test fun everySourceFailingChangesNothing() = runTest {
        val store = FakeSource(listOf(found("content://media/1", "Mars.mp3")))
        val other = FakeSource(listOf(found("content://other/1", "Venus.mp3")), prefix = "content://other/")
        val scanner = scanner(store, other)
        scanner.scan()
        val before = phoneTracks()

        store.error = IOException("MediaStore unavailable")
        other.error = IOException("provider died")
        assertThat(scanner.scan()).isEqualTo(0)
        assertThat(phoneTracks()).isEqualTo(before)
        assertThat(playback.scanCacheUris()).hasSize(2)
    }

    @Test fun deletedFolderItemsGoMissing() = runTest {
        val fakeTree = FakeTree()
        val stories = fakeTree.tree("primary:Stories", "Stories", listOf("1.mp3"))
        val tapes = fakeTree.tree("primary:Tapes", "Tapes", listOf("side-a.mp3"))
        val folders = listOf(MediaFolder(stories.toString(), true, 1L), MediaFolder(tapes.toString(), false, 2L))
        val scanner = scanner(FolderSource(ApplicationProvider.getApplicationContext(), { folders }, fakeTree))
        scanner.scan()

        fakeTree.roots.remove(tapes) // deleted on the phone, or its SD card taken out: gone, not unknown
        scanner.scan()
        assertThat(phoneTracks().associate { it.title to it.status }).containsExactly("1", PackStatus.OK, "side-a", PackStatus.MISSING)
        assertThat(playback.scanCacheUris()).containsExactly(DocumentsContract.buildDocumentUriUsingTree(stories, "primary:Stories/1.mp3").toString())
    }

    @Test fun failingFolderIsIsolatedFromOtherFolders() = runTest {
        val fakeTree = FakeTree()
        val stories = fakeTree.tree("primary:Stories", "Stories", listOf("1.mp3"))
        val tapes = fakeTree.tree("primary:Tapes", "Tapes", listOf("side-a.mp3"))
        val folders = listOf(MediaFolder(stories.toString(), true, 1L), MediaFolder(tapes.toString(), false, 2L))
        val scanner = scanner(FolderSource(ApplicationProvider.getApplicationContext(), { folders }, fakeTree))
        scanner.scan()
        val sideA = phoneTracks().single { it.title == "side-a" }

        fakeTree.failing += "primary:Tapes"
        fakeTree.addFiles(stories, "primary:Stories", listOf("2.mp3"))
        scanner.scan()
        assertThat(phoneTracks().single { it.title == "side-a" }).isEqualTo(sideA)
        assertThat(phoneTracks().filter { it.title != "side-a" }.map { it.title to it.status })
            .containsExactly("1" to PackStatus.OK, "2" to PackStatus.OK)
    }

    @Test fun scanCacheDropsVanishedFiles() = runTest {
        val source = FakeSource(listOf(found("content://media/1", "Mars.mp3"), found("content://media/2", "Venus.mp3")))
        val scanner = scanner(source)
        scanner.scan()
        assertThat(playback.scanCacheUris()).containsExactly("content://media/1", "content://media/2")

        source.files = source.files.take(1)
        scanner.scan()
        assertThat(playback.scanCacheUris()).containsExactly("content://media/1")
    }

    @Test fun scannerNeverOpensForWrite() = runTest {
        val cover = "content://media/cover"
        disk[cover] = tmp.newFile().apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)) }
        tags["embedded.mp3"] = FileTags(raw("embedded", cover = byteArrayOf(9, 9, 9)), emptyList())
        val files = listOf(
            found("content://media/1", "embedded.mp3", "Music/A/"),
            found("content://media/2", "bare.mp3", "Music/B/", folderCoverUri = cover),
        )
        scanner(FakeSource(files)).scan()

        assertThat(opened).containsAtLeast("content://media/1", "content://media/2", cover)
        assertThat(modes).isNotEmpty()
        assertThat(modes.distinct()).containsExactly("r")
        // Covers land in Granger's own storage only.
        val covers = phoneTracks().associate { it.title to it.coverPath }
        assertThat(File(covers.getValue("embedded")!!).readBytes()).isEqualTo(byteArrayOf(9, 9, 9))
        assertThat(File(covers.getValue("bare")!!).parentFile).isEqualTo(coversDir)
        assertThat(File(covers.getValue("embedded")!!).name).isEqualTo("${fingerprintOf("content://media/1")}.jpg")
    }

    @Test fun concurrentScanIsJoinedNotRepeated() = runTest {
        val source = FakeSource(listOf(found("content://media/1", "Mars.mp3")))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val scanner = scanner(source)
        val first = async { scanner.scan() }
        val second = async { scanner.scan() }
        runCurrent()
        gate.complete(Unit)
        assertThat(first.await()).isEqualTo(1)
        assertThat(second.await()).isEqualTo(1)
        assertThat(source.calls).isEqualTo(1)

        // Once it's done, the next call scans again.
        source.gate = null
        scanner.scan()
        assertThat(source.calls).isEqualTo(2)
    }

    @Test fun sameFileFromTwoSourcesIsOneTrack() = runTest {
        val bytes = Random(5).nextBytes(80_000)
        val a = FakeSource(listOf(found("content://media/1", "Mars.mp3", bytes = bytes)))
        val b = FakeSource(listOf(found("content://tree/1", "Mars.mp3", bytes = bytes)))
        scanner(a, b).scan()
        assertThat(phoneTracks().map { it.uri }).containsExactly("content://media/1")
    }
}
