package app.granger.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.library.AudioBookmark
import app.granger.core.library.AudiobookState
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaOrigin
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.RoomShelves
import app.granger.core.media.DownloadedIds
import app.granger.core.media.DownloadedMediaScanner
import app.granger.core.media.FileTags
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.core.media.TagReader
import app.granger.formatBytes
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
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
class MediaDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .addCallback(LibraryDatabase.SeedCallback)
        .allowMainThreadQueries()
        .build()
    private val media = RoomMediaLibrary(db)
    private val mediaDao = db.mediaDao()
    private val playback = db.playbackDao()
    private val shelves = RoomShelves(db, FakeLibrary(), FakeBookshelf(), media)

    private val internal by lazy { tmp.newFolder("internal") }
    private val sdCard by lazy { tmp.newFolder("sd") }
    private val downloads = FakeDownloads()
    private val volumes by lazy { FakeVolumes(listOf(volume(internal.path, "Internal storage"))) }
    private val prefs = FakePrefs()
    private val untagged = TagReader { _, _ -> FileTags(null, emptyList()) }
    private val scanner by lazy { DownloadedMediaScanner({ volumes.volumes.map { it.root } }, media, untagged) }

    @After fun close() = db.close()

    private fun TestScope.downloader() = MediaDownloader(downloads, scanner, media, volumes, prefs, backgroundScope)

    private val book = MediaFixtures.timeMachine()
    private val group = "media:librivox:817"
    private val bookId = "audiobook:librivox:817"
    private fun dir(root: File = internal, m: MediaManifest = book) = File(root, MediaManifests.relativeDir(m))

    /** The files at [indices] arrive and their records turn INSTALLED, as the runner would leave them. */
    private fun arrive(indices: Iterable<Int>, root: File = internal) {
        for (i in indices) MediaFixtures.arrive(root, book, book.files[i])
        val names = indices.map { book.files[it].name }.toSet()
        downloads.list.update { list ->
            list.map { r -> if (r.groupId == group && File(r.filePath).name in names) r.copy(state = DownloadState.INSTALLED, bytesDone = r.sizeBytes) else r }
        }
    }

    private fun installed(i: Int) = record(MediaDownloads.fileItemId(group, i + 1), DownloadState.INSTALLED, kind = ItemKind.AUDIO,
        filePath = File(dir(), book.files[i].name).path).copy(groupId = group)

    @Test fun getWritesTheManifestBeforeQueueing() = runTest {
        var manifestAtEnqueue: MediaManifest? = null
        downloads.onEnqueue = { if (manifestAtEnqueue == null) manifestAtEnqueue = MediaManifests.read(dir()) }
        assertThat(downloader().get(book)).isNull()
        assertThat(manifestAtEnqueue!!.ref).isEqualTo("817")
        assertThat(manifestAtEnqueue!!.files).hasSize(13)
        val (groupId, specs, root) = downloads.enqueuedGroups.single()
        assertThat(groupId).isEqualTo(group)
        assertThat(specs).isEqualTo(MediaDownloads.specs(book))
        assertThat(root).isEqualTo(internal)
        assertThat(File(internal, "audiobooks/.nomedia").isFile).isTrue()
    }

    @Test fun refusedGetLeavesNoFolder() = runTest {
        downloads.nextResult = EnqueueResult.NoSpace(5_000_000)
        val notice = downloader().get(book)
        assertThat(notice).isEqualTo("Not enough space on Internal storage for Time Machine: free up ${formatBytes(5_000_000)}.")
        assertThat(dir().exists()).isFalse()
    }

    @Test fun refusedGetTheRestKeepsTheFilesAlreadyIn() = runTest {
        val d = downloader()
        d.get(book)
        arrive(0 until 3)
        downloads.nextResult = EnqueueResult.NoSpace(1_000)
        assertThat(d.get(book)).startsWith("Not enough space on Internal storage")
        assertThat((0 until 3).all { File(dir(), book.files[it].name).isFile }).isTrue()
        assertThat(MediaManifests.read(dir())).isNotNull()
    }

    @Test fun noVolumeIsANotice() = runTest {
        volumes.volumes = emptyList()
        assertThat(downloader().get(book)).isEqualTo("No storage is available right now.")
        assertThat(downloads.enqueuedGroups).isEmpty()
    }

    @Test fun fileInstalledFilesTheItem() = runTest {
        val d = downloader()
        d.get(book)
        arrive(0 until 3)
        d.onFileInstalled(installed(2))
        val audiobook = media.audiobook(bookId)!!
        assertThat(audiobook.title).isEqualTo("Time Machine")
        assertThat(media.chapters(bookId).first().map { it.trackId })
            .containsExactlyElementsIn((0 until 3).map { DownloadedIds.trackId(book, book.files[it]) }).inOrder()
    }

    @Test fun fileInstalledNeverThrows() = runTest {
        val broken = DownloadedMediaScanner({ throw IllegalStateException("no roots") }, media, untagged)
        val d = MediaDownloader(downloads, broken, media, volumes, prefs, backgroundScope)
        d.onFileInstalled(installed(0))
    }

    /** "Show in other apps": the Files app is told after a file is filed and after a removal, once the folder is gone. */
    @Test fun otherAppsAreToldAfterFilingAndRemoval() = runTest {
        val seen = mutableListOf<Boolean>()
        val d = MediaDownloader(downloads, scanner, media, volumes, prefs, backgroundScope, onChanged = { seen += dir().exists() })
        d.get(book)
        arrive(0 until 2)
        d.onFileInstalled(installed(1))
        assertThat(seen).containsExactly(true)
        d.remove(group)
        assertThat(seen).containsExactly(true, false).inOrder()
    }

    @Test fun removeForgetsEveryTrackOfTheBookKeepingStateAndBookmarks() = runTest {
        val d = downloader()
        d.get(book)
        arrive(book.files.indices)
        d.onFileInstalled(installed(0))
        val first = DownloadedIds.trackId(book, book.files[0])
        val state = AudiobookState(bookId, first, 42_000L, 1.25f, false, 0, false, 5L)
        playback.upsertAudiobookState(state)
        val mark = playback.insertBookmark(AudioBookmark(0, bookId, first, 40_000L, "Morlocks", "", 6L))

        d.remove(group)
        assertThat(downloads.calls).contains("removeGroup:$group")
        assertThat(dir().exists()).isFalse()
        assertThat(media.audiobook(bookId)).isNull()
        // No orphan rows: every file track of the book went with it.
        assertThat(mediaDao.tracksOf(MediaOrigin.LIBRIVOX)).isEmpty()
        assertThat(media.chapters(bookId).first()).isEmpty()
        assertThat(playback.audiobookState(bookId)).isEqualTo(state)
        assertThat(playback.observeBookmarks(bookId).first().map { it.id }).containsExactly(mark)
    }

    @Test fun removeThenGetAgainRestoresPositionAndPlacement() = runTest {
        val d = downloader()
        d.get(book)
        arrive(book.files.indices)
        d.onFileInstalled(installed(0))
        val audiobook = media.audiobook(bookId)!!
        val second = DownloadedIds.trackId(book, book.files[1])
        val state = AudiobookState(bookId, second, 61_000L, 1f, false, 0, false, 7L)
        playback.upsertAudiobookState(state)
        val mine = shelves.createCollection("Bedtime", "audiobooks")
        shelves.moveAudiobook(audiobook, mine)
        assertThat(shelves.collection(mine).first()!!.audiobooks.map { it.id }).containsExactly(bookId)

        d.remove(group)
        assertThat(media.audiobook(bookId)).isNull()
        assertThat(dir().exists()).isFalse()
        assertThat(shelves.shelves.first().flatMap { s -> s.collections.flatMap { it.audiobooks } }).isEmpty()

        assertThat(d.get(book)).isNull()
        arrive(book.files.indices)
        d.onFileInstalled(installed(0))
        assertThat(media.audiobook(bookId)!!.id).isEqualTo(audiobook.id)
        assertThat(media.chapters(bookId).first()).hasSize(13)
        assertThat(playback.audiobookState(bookId)).isEqualTo(state)
        assertThat(shelves.collection(mine).first()!!.audiobooks.map { it.id }).containsExactly(bookId)
    }

    @Test fun cancelKeepsFinishedFiles() = runTest {
        val d = downloader()
        d.get(book)
        arrive(0 until 2)
        d.onFileInstalled(installed(1))
        d.cancel(group)
        assertThat(downloads.calls).contains("cancelGroup:$group")
        assertThat((0 until 2).all { File(dir(), book.files[it].name).isFile }).isTrue()
        assertThat(media.chapters(bookId).first()).hasSize(2)
    }

    /** One item, one volume: the scanner would file only the first of two copies. */
    @Test fun getCompletesAnItemOnTheVolumeItIsAlreadyOn() = runTest {
        volumes.volumes = listOf(volume(internal.path, "Internal storage"), volume(sdCard.path, "SD card"))
        prefs.volumePath = sdCard.path
        val d = downloader()
        d.get(book)
        assertThat(downloads.enqueuedGroups.last().third).isEqualTo(sdCard)
        arrive(0 until 2, root = sdCard)

        prefs.volumePath = internal.path
        assertThat(d.get(book)).isNull()
        assertThat(downloads.enqueuedGroups.last().third).isEqualTo(sdCard)
        assertThat(dir(internal).exists()).isFalse()
    }

    @Test fun getRefusesAnItemOnStorageThatIsNotMounted() = runTest {
        downloads.list.value = listOf(
            record("$group/001", DownloadState.INSTALLED, kind = ItemKind.AUDIO, filePath = "/gone/sd/audiobooks/librivox/817/01 - x.mp3")
                .copy(groupId = group),
        )
        val notice = downloader().get(book)
        assertThat(notice).isEqualTo("Time Machine is on storage that isn't available right now. Put it back to finish it, or remove it first.")
        assertThat(downloads.enqueuedGroups).isEmpty()
        assertThat(dir().exists()).isFalse()
    }

    @Test fun getAgainKeepsTagsAlreadyCached() = runTest {
        val d = downloader()
        d.get(book)
        arrive(0 until 1)
        d.onFileInstalled(installed(0))
        assertThat(MediaManifests.read(dir())!!.files[0].tags).isNotNull()
        d.get(book)
        assertThat(MediaManifests.read(dir())!!.files[0].tags).isNotNull()
    }

    /**
     * A worker still running when Remove deleted the folder recreates it (FileDownloader's mkdirs) and leaves a `.part`.
     * After the removal's rescan, item folders in Granger's media roots with no manifest and no ledger rows go; nothing
     * else is touched.
     */
    @Test fun removeDeletesAFolderAWorkerRecreatedWithAStrayPart() = runTest {
        val stray = File(dir(), "01 - x.mp3.part")
        val d = MediaDownloader(downloads, scanner, media, volumes, prefs, backgroundScope, onChanged = {
            if (!dir().exists()) stray.apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        })
        d.get(book)
        arrive(0 until 1)
        d.onFileInstalled(installed(0))

        // Left alone: an item with a manifest, a manifest-less folder the ledger still downloads into, and anything
        // that isn't an item folder in a media root.
        val other = MediaFixtures.timeMachine().copy(ref = "900")
        MediaManifests.write(dir(m = other).apply { mkdirs() }, other)
        val active = File(internal, "audiobooks/librivox/901").apply { mkdirs() }
        File(active, "01.mp3.part").writeBytes(byteArrayOf(1))
        downloads.list.update {
            it + record("media:librivox:901/001", DownloadState.RUNNING, kind = ItemKind.AUDIO,
                filePath = File(active, "01.mp3").path).copy(groupId = "media:librivox:901")
        }
        val rootFile = File(internal, "audiobooks/librivox/notes.txt").apply { writeText("x") }
        val elsewhere = File(internal, "books/librivox/902").apply { mkdirs() }
        File(elsewhere, "keep.bin").writeBytes(byteArrayOf(1))

        d.remove(group)
        assertThat(stray.exists()).isFalse()
        assertThat(dir().exists()).isFalse()
        assertThat(MediaManifests.read(dir(m = other))).isNotNull()
        assertThat(File(active, "01.mp3.part").isFile).isTrue()
        assertThat(rootFile.isFile).isTrue()
        assertThat(File(elsewhere, "keep.bin").isFile).isTrue()
        assertThat(File(internal, "audiobooks/.nomedia").isFile).isTrue()
    }

    /** Cancelled before any audio file arrived: nothing worth keeping, so the folder (manifest, cover) and its rows go. */
    @Test fun cancelBeforeAnyAudioArrivesLeavesNoFolder() = runTest {
        val d = downloader()
        d.get(book)
        assertThat(book.cover).isNotNull()
        File(dir(), MediaDownloads.COVER_NAME).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        downloads.list.update { list -> list.map { if (MediaDownloads.isCover(it)) it.copy(state = DownloadState.INSTALLED) else it } }

        d.cancel(group)
        assertThat(dir().exists()).isFalse()
        assertThat(downloads.records.first().filter { it.groupId == group }).isEmpty()
        assertThat(media.audiobook(bookId)).isNull()
        assertThat(File(internal, "audiobooks/.nomedia").isFile).isTrue()
    }
}
