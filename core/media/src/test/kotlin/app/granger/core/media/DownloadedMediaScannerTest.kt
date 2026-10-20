package app.granger.core.media

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.AlbumNote
import app.granger.core.library.Albums
import app.granger.core.library.AudiobookState
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.ScanResult
import app.granger.core.library.Track
import app.granger.core.media.ManifestFixtures.HOLST
import app.granger.core.media.ManifestFixtures.HOLST_NAMES
import app.granger.core.taglib.RawTags
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadedMediaScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val media = RoomMediaLibrary(db)
    private val mediaDao = db.mediaDao()
    private val playback = db.playbackDao()

    private val internal by lazy { tmp.newFolder("internal") }
    private val sdCard by lazy { tmp.newFolder("sd") }
    private var roots: List<File> = emptyList()

    /** Tags by local file name; a file not listed has none (an untagged file, as most of the Holst item's are). */
    private val tags = mutableMapOf<String, FileTags>()
    private val reads = mutableListOf<String>()
    private val tagReader = TagReader { pfd, f ->
        reads += f.displayName
        // Opened read-only: the descriptor refuses writes.
        assertThrows(IOException::class.java) { FileOutputStream(pfd.fileDescriptor).write(1) }
        tags[f.displayName] ?: FileTags(RawTags(emptyMap(), 0L, 44_100, 128, 2, null), emptyList())
    }

    private fun scanner(guard: TagReadGuard = TagReadGuard.NONE, reader: TagReader = tagReader) =
        DownloadedMediaScanner({ roots }, media, reader, guard)

    @After fun close() = db.close()

    /** Writes [m] into its folder on [root], with every file in [present] (by index) written at its full size. */
    private fun install(root: File, m: MediaManifest, present: Iterable<Int> = m.files.indices, cover: Boolean = true): File {
        val dir = File(root, MediaManifests.relativeDir(m)).apply { mkdirs() }
        MediaManifests.write(dir, m)
        for (i in present) arrive(dir, m.files[i])
        if (cover) m.cover?.let { arrive(dir, it) }
        return dir
    }

    private fun arrive(dir: File, f: ManifestFile) = File(dir, f.name).writeBytes(ByteArray(f.sizeBytes.toInt()) { 7 })

    private fun raw(title: String = "", album: String = "", durationMs: Long = 0L, cover: ByteArray? = null) = RawTags(
        properties = buildMap {
            if (title.isNotEmpty()) put("TITLE", listOf(title))
            if (album.isNotEmpty()) put("ALBUM", listOf(album))
        },
        durationMs = durationMs, sampleRate = 44_100, bitrateKbps = 128, channels = 2, cover = cover,
    )

    private suspend fun tracksOf(origin: MediaOrigin) = mediaDao.tracksOf(origin)
    private suspend fun booksOf(origin: MediaOrigin) = mediaDao.audiobooksOf(origin)

    @Test fun archiveAlbumIsOneAlbumWhateverItsTags() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        install(internal, holst)
        // Six files carry no tags at all; Venus names itself as its own album.
        tags[holst.files[1].name] = FileTags(raw(title = "Venus, The Bringer of Peace", album = "Venus"), emptyList())

        assertThat(scanner().scan()).isEqualTo(7)

        val albums = Albums.summaries(media.tracks.first())
        assertThat(albums).hasSize(1)
        assertThat(albums.single().key).isEqualTo(Albums.itemKey("archive-org", HOLST))
        assertThat(albums.single().trackCount).isEqualTo(7)
        assertThat(albums.single().title).isEqualTo("The Planets Op. 32, H. 125")
        assertThat(albums.single().albumArtist).isEqualTo("Gustav Holst")
        val ordered = Albums.ordered(media.tracks.first())
        assertThat(ordered.map { it.title }[1]).isEqualTo("Venus, The Bringer of Peace")
        // Untagged files are named from their remote names; nothing reads "Venus" as an album.
        assertThat(ordered.first().title).isEqualTo("The Planets Op. 32, H. 125_ I. Mars, The Bringer of War")
        assertThat(ordered.map { it.album }.toSet()).containsExactly("The Planets Op. 32, H. 125")
        assertThat(ordered.map { it.trackNo }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(ordered.all { it.origin == MediaOrigin.ARCHIVE_ORG && it.status == PackStatus.OK }).isTrue()
        assertThat(ordered.first().folder).isEqualTo("music/archive-org/$HOLST/")
        assertThat(ordered.first().uri).isEqualTo(Uri.fromFile(File(internal, "music/archive-org/$HOLST/${holst.files[0].name}")).toString())
        assertThat(ordered.first().id).isEqualTo(DownloadedIds.trackId(holst, holst.files[0]))
        assertThat(booksOf(MediaOrigin.ARCHIVE_ORG)).isEmpty()
    }

    @Test fun albumManifestDescriptionBecomesAnAlbumNote() = runTest {
        roots = listOf(internal)
        install(internal, ManifestFixtures.holst().copy(description = "  The London Symphony Orchestra, conducted by the composer, 1922–24. "))
        install(internal, ManifestFixtures.timeMachine())
        scanner().scan()

        // The album's note is its description; an audiobook's description stays on the audiobook, not in a note.
        assertThat(media.albumNotes.first()).containsExactly(
            AlbumNote(Albums.itemKey("archive-org", HOLST), "The London Symphony Orchestra, conducted by the composer, 1922–24."),
        )
    }

    @Test fun albumWithoutADescriptionHasNoNote() = runTest {
        roots = listOf(internal)
        install(internal, ManifestFixtures.holst())
        scanner().scan()
        assertThat(media.albumNotes.first()).isEmpty()
    }

    @Test fun librivoxBookHasOneChapterPerSectionWithReaders() = runTest {
        roots = listOf(internal)
        val m = ManifestFixtures.timeMachine(sections = 3)
        val dir = install(internal, m)
        scanner().scan()

        val book = booksOf(MediaOrigin.LIBRIVOX).single()
        assertThat(book.id).isEqualTo("audiobook:librivox:817")
        assertThat(book.title).isEqualTo("Time Machine")
        assertThat(book.author).isEqualTo("H. G. Wells")
        assertThat(book.narrator).isEqualTo("Kristin Hughes; Mark Nelson")
        assertThat(book.description).isEqualTo("A Victorian scientist travels.")
        assertThat(book.sourceRef).isEqualTo("817")
        assertThat(book.coverPath).isEqualTo(File(dir, "cover.jpg").path)
        assertThat(book.durationMs).isEqualTo(600_000L + 600_001L + 600_002L)

        val chapters = media.chapters(book.id).first()
        assertThat(chapters.map { it.title }).containsExactly("Chapter 01", "Chapter 02", "Chapter 03").inOrder()
        assertThat(chapters.map { it.trackId }).containsExactly("track:librivox:817:4000", "track:librivox:817:4001", "track:librivox:817:4002").inOrder()

        val tracks = tracksOf(MediaOrigin.LIBRIVOX).sortedBy { it.trackNo }
        assertThat(tracks.map { it.artists }).containsExactly("Kristin Hughes", "Mark Nelson", "Kristin Hughes").inOrder()
        assertThat(tracks.map { it.audiobookId }.toSet()).containsExactly(book.id)
        assertThat(tracks.first().genres).isEqualTo("Science Fiction")
        assertThat(tracks.first().coverPath).isEqualTo(book.coverPath)
        // Audiobook files aren't music.
        assertThat(media.tracks.first()).isEmpty()
    }

    @Test fun bookIsPlayableOnceItsFirstFileArrives() = runTest {
        roots = listOf(internal)
        val m = ManifestFixtures.timeMachine(sections = 3)
        val dir = install(internal, m, present = listOf(0), cover = false)
        val scanner = scanner()
        scanner.scan()
        val id = booksOf(MediaOrigin.LIBRIVOX).single().id
        assertThat(media.chapters(id).first()).hasSize(1)
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().coverPath).isNull()

        arrive(dir, m.files[1])
        scanner.scan()
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().id).isEqualTo(id)
        assertThat(media.chapters(id).first().map { it.title }).containsExactly("Chapter 01", "Chapter 02").inOrder()
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().durationMs).isEqualTo(600_000L + 600_001L)
    }

    @Test fun startupScanFilesAFileInstalledBeforeProcessDeath() = runTest {
        // The worker verified and renamed the file, then the process died before filing it: no rows yet.
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        install(internal, holst, present = listOf(0))
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG)).isEmpty()

        scanner().scan()   // the next start's scan
        val track = tracksOf(MediaOrigin.ARCHIVE_ORG).single()
        assertThat(track.id).isEqualTo(DownloadedIds.trackId(holst, holst.files[0]))
        assertThat(track.status).isEqualTo(PackStatus.OK)
    }

    @Test fun tagsAreReadOnceAndCachedInTheManifest() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        val dir = install(internal, holst, present = listOf(0, 1))
        tags[holst.files[0].name] = FileTags(raw(title = "Mars", durationMs = 420_000L), emptyList())
        assertThat(scanner().scan()).isEqualTo(2)
        assertThat(reads).hasSize(2)

        val cached = MediaManifests.read(dir)!!
        assertThat(cached.files[0].tags).isEqualTo(CachedTags("Mars", 420_000L, null, null, null, null, null))
        assertThat(cached.files[1].tags).isEqualTo(CachedTags("", 0L, null, null, null, null, null))
        assertThat(cached.files[2].tags).isNull()   // not present yet

        // A later scan (a new process) never opens them again, and files from the cache.
        tags.clear()
        reads.clear()
        assertThat(scanner().scan()).isEqualTo(0)
        assertThat(reads).isEmpty()
        val mars = tracksOf(MediaOrigin.ARCHIVE_ORG).single { it.trackNo == 1 }
        assertThat(mars.title).isEqualTo("Mars")
        assertThat(mars.durationMs).isEqualTo(420_000L)

        // A new file is read; the cached ones still aren't.
        arrive(dir, holst.files[2])
        assertThat(scanner().scan()).isEqualTo(1)
        assertThat(reads).containsExactly(holst.files[2].name)
    }

    @Test fun replayGainAndEmbeddedArtComeFromTheTagsAndArtIsStoredOnce() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        val dir = install(internal, holst, present = listOf(0, 1, 2))
        val art = byteArrayOf(1, 2, 3, 4)
        val gain = RawTags(mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("-6.5 dB"), "REPLAYGAIN_ALBUM_PEAK" to listOf("0.9")), 0L, 44_100, 128, 2, art)
        tags[holst.files[0].name] = FileTags(gain, emptyList())
        tags[holst.files[1].name] = FileTags(raw(cover = art.copyOf()), emptyList())
        tags[holst.files[2].name] = FileTags(raw(cover = byteArrayOf(9)), emptyList())
        scanner().scan()

        val byNo = tracksOf(MediaOrigin.ARCHIVE_ORG).associateBy { it.trackNo }
        assertThat(byNo.getValue(1).rgTrackGain).isEqualTo(-6.5f)
        assertThat(byNo.getValue(1).rgAlbumPeak).isEqualTo(0.9f)
        assertThat(byNo.getValue(1).coverPath).isEqualTo(byNo.getValue(2).coverPath)
        assertThat(byNo.getValue(3).coverPath).isNotEqualTo(byNo.getValue(1).coverPath)
        val cover = File(byNo.getValue(1).coverPath!!)
        assertThat(cover.parentFile).isEqualTo(dir)
        assertThat(cover.name).matches("\\.cover-[0-9a-f]{16}\\.jpg")
        assertThat(cover.readBytes()).isEqualTo(art)
        assertThat(dir.list()!!.filter { it.startsWith(".cover-") }).hasSize(2)
        assertThat(MediaManifests.read(dir)!!.files[0].tags!!.embeddedCover).isEqualTo(cover.name)
    }

    @Test fun crashedFileIsNeverReadForTagsAgain() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        val dir = install(internal, holst, present = listOf(0))
        val guardDir = tmp.newFolder("guard")
        val crashing = TagReader { _, f -> throw StackOverflowError(f.uri) }   // an Error: the stand-in for a native crash
        runCatching { scanner(FileTagReadGuard(guardDir), crashing).scan() }

        // The next run skips it, and files it under its own name.
        assertThat(scanner(FileTagReadGuard(guardDir)).scan()).isEqualTo(0)
        assertThat(reads).isEmpty()
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG).single().title).isEqualTo(HOLST_NAMES[0].removeSuffix(".mp3"))
        assertThat(MediaManifests.read(dir)!!.files[0].tags).isNotNull()
    }

    @Test fun missingVolumeMarksItsItemsMissingAndKeepsState() = runTest {
        roots = listOf(internal, sdCard)
        install(internal, ManifestFixtures.holst())
        install(sdCard, ManifestFixtures.timeMachine())
        val scanner = scanner()
        scanner.scan()
        val book = booksOf(MediaOrigin.LIBRIVOX).single()
        val state = AudiobookState(book.id, "track:librivox:817:4001", 90_000L, 1.25f, false, 0, false, 5L)
        playback.upsertAudiobookState(state)

        roots = listOf(internal)   // the SD card is out
        scanner.scan()
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().status).isEqualTo(PackStatus.MISSING)
        assertThat(tracksOf(MediaOrigin.LIBRIVOX).map { it.status }.toSet()).containsExactly(PackStatus.MISSING)
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG).map { it.status }.toSet()).containsExactly(PackStatus.OK)
        assertThat(playback.audiobookState(book.id)).isEqualTo(state)

        roots = listOf(internal, sdCard)   // back in
        scanner.scan()
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().status).isEqualTo(PackStatus.OK)
        assertThat(playback.audiobookState(book.id)).isEqualTo(state)
    }

    @Test fun downloadedMediaIsFiledWithoutTheAudioPermission() = runTest {
        roots = listOf(internal)
        install(internal, ManifestFixtures.timeMachine())
        val phone = Track(
            id = "track:phone:abc", origin = MediaOrigin.PHONE, uri = "content://media/external/audio/media/1", folder = "Music/",
            title = "Phone song", album = "", albumKey = Albums.key("", "", "Music/"), albumArtists = "", artists = "", composers = "",
            genres = "", disc = null, trackNo = null, date = "", originalDate = "", releaseType = "", sortTitle = "", sortAlbum = "",
            sortArtist = "", sortAlbumArtist = "", durationMs = 1L, rgTrackGain = null, rgTrackPeak = null, rgAlbumGain = null,
            rgAlbumPeak = null, coverPath = null, audiobookId = null, status = PackStatus.OK, addedAt = 0L,
        )
        media.sync(MediaOrigin.PHONE, ScanResult(listOf(phone), emptyList()))

        // The downloaded scan never touches phone rows.
        scanner().scan()
        assertThat(tracksOf(MediaOrigin.PHONE).single().status).isEqualTo(PackStatus.OK)

        val phoneScanner = PhoneScanner(emptyList(), { null }, media, playback, tmp.newFolder("covers"), hasPermission = { false })
        phoneScanner.scan()
        scanner().scan()
        assertThat(tracksOf(MediaOrigin.PHONE).single().status).isEqualTo(PackStatus.MISSING)
        assertThat(tracksOf(MediaOrigin.LIBRIVOX).map { it.status }.toSet()).containsExactly(PackStatus.OK)
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().status).isEqualTo(PackStatus.OK)
    }

    @Test fun partFilesAndQueuedFilesAreNotFiled() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        val dir = install(internal, holst, present = emptyList())
        File(dir, holst.files[0].name + ".part").writeBytes(ByteArray(holst.files[0].sizeBytes.toInt()))
        // A file of the wrong size isn't the verified download.
        File(dir, holst.files[1].name).writeBytes(ByteArray(10))
        scanner().scan()
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG)).isEmpty()
        assertThat(reads).isEmpty()
    }

    @Test fun brokenManifestSkipsOnlyThatItem() = runTest {
        roots = listOf(internal)
        val holstDir = install(internal, ManifestFixtures.holst())
        val bookDir = install(internal, ManifestFixtures.timeMachine())
        val scanner = scanner()
        scanner.scan()
        assertThat(scanner.lastUnreadable).isEmpty()

        File(bookDir, MediaManifests.FILE_NAME).writeText("""{"version":1,"source":"libri""")   // a torn write
        File(internal, "music/archive-org/other-item").mkdirs()
        File(internal, "music/archive-org/other-item/${MediaManifests.FILE_NAME}").writeText("not json")
        scanner.scan()

        assertThat(scanner.lastUnreadable).containsExactly(bookDir, File(internal, "music/archive-org/other-item"))
        assertThat(scanner.items().map { it.first }).containsExactly(holstDir)
        // The item that couldn't be read is unknown, not gone: its rows stay as they were.
        assertThat(booksOf(MediaOrigin.LIBRIVOX).single().status).isEqualTo(PackStatus.OK)
        assertThat(tracksOf(MediaOrigin.LIBRIVOX).map { it.status }.toSet()).containsExactly(PackStatus.OK)
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG)).hasSize(7)
    }

    @Test fun manifestInTheWrongFolderOrWithUnsafeNamesIsSkipped() = runTest {
        roots = listOf(internal)
        val holst = ManifestFixtures.holst()
        // A manifest whose ref doesn't name its own folder.
        val elsewhere = File(internal, "music/archive-org/not-holst").apply { mkdirs() }
        MediaManifests.write(elsewhere, holst)
        // A file name that would leave the item folder.
        val escaping = holst.copy(ref = "esc", archiveIdentifier = "esc", title = "esc",
            files = listOf(holst.files[0].copy(name = "../../../escape.mp3")))
        val escDir = File(internal, "music/archive-org/esc").apply { mkdirs() }
        MediaManifests.write(escDir, escaping)
        val scanner = scanner()
        scanner.scan()
        assertThat(tracksOf(MediaOrigin.ARCHIVE_ORG)).isEmpty()
        assertThat(scanner.lastUnreadable).containsExactly(elsewhere, escDir)
    }
}
