package app.granger.media

import app.granger.core.catalog.CatalogItem
import app.granger.core.downloads.DownloadLive
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadSpec
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.DownloadVolume
import app.granger.core.downloads.DownloadVolumes
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.library.AlbumNote
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MediaOrigin
import app.granger.core.library.ScanResult
import app.granger.core.library.Track
import app.granger.core.media.DownloadedMediaScan
import app.granger.core.media.FileTags
import app.granger.core.media.ManifestFile
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.core.media.PathTagReader
import app.granger.sources.DownloadPrefs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/**
 * Plan I6 Task 3: a LibriVox book got through the shared media downloader is one group on the queue, `media:librivox:<id>`,
 * and once its files are in, the shared downloaded-media scanner files them into `tracks` and `audiobooks` with origin
 * LIBRIVOX. The queue is a stub that "downloads" each file at once; the library is in memory. Run on the JVM (Robolectric,
 * for the file uris) by LibriVoxGroupDownloadTest and on the Simulator by LibriVoxGroupDownloadIosTest.
 */
abstract class LibriVoxGroupDownloadChecks {
    private val root = Path(SystemTemporaryDirectory, "granger-media-${Random.nextLong().toULong()}")

    @AfterTest fun tidy() = deleteTree(root)

    private fun file(i: Int, section: String, title: String) = ManifestFile(
        name = MediaManifests.localName(i, title, "timemachine_${i + 1}_wells_64kb.mp3"),
        remoteName = "timemachine_${i + 1}_wells_64kb.mp3",
        url = "https://archive.org/download/timemachine_librivox/timemachine_${i + 1}_wells_64kb.mp3",
        sizeBytes = 16L + i, hash = "a".repeat(40), hashAlgorithm = "SHA1",
        title = title, trackNo = i + 1, readers = listOf("Mark Nelson"), durationMs = 600_000L, sectionId = section,
    )

    private val book = MediaManifest(
        source = MediaManifest.LIBRIVOX, kind = MediaManifest.AUDIOBOOK, ref = "817",
        archiveIdentifier = "timemachine_librivox", pageUrl = "https://librivox.org/the-time-machine-by-h-g-wells/",
        title = "The Time Machine", creators = listOf("H. G. Wells"), readers = listOf("Mark Nelson"),
        files = listOf(file(0, "4101", "Chapter 1"), file(1, "4102", "Chapter 2")), gotAt = 0L,
    )

    @Test fun aLibriVoxBookIsFiledIntoTracksAndAudiobooks() = runTest {
        SystemFileSystem.createDirectories(root)
        val media = MemoryMedia()
        val downloads = InstantDownloads()
        val untagged = PathTagReader { _, _, _ -> FileTags(null, emptyList()) }
        val scanner = DownloadedMediaScan({ listOf(root.toString()) }, media, untagged)
        val volumes = DownloadVolumes { listOf(DownloadVolume(root.toString(), "Phone", false, 1L shl 40, 1L shl 40, 0L)) }
        val downloader = MediaItemDownloader(downloads, scanner, media, volumes, Prefs(), backgroundScope, UnconfinedTestDispatcher(testScheduler))

        assertNull(downloader.get(book))
        assertEquals("media:librivox:817", downloads.group)
        assertEquals(listOf("media:librivox:817/001", "media:librivox:817/002"), downloads.records.value.map { it.itemId })
        for (record in downloads.records.value) downloader.onFileInstalled(record)

        val bookId = "audiobook:librivox:817"
        val tracks = media.trackList.value
        assertEquals(listOf("track:librivox:817:4101", "track:librivox:817:4102"), tracks.map { it.id })
        assertEquals(setOf(MediaOrigin.LIBRIVOX), tracks.map { it.origin }.toSet())
        assertEquals(listOf("Chapter 1", "Chapter 2"), tracks.map { it.title })
        assertEquals(setOf(bookId), tracks.map { it.audiobookId }.toSet())
        val filed = media.bookList.value.single()
        assertEquals(bookId, filed.id)
        assertEquals(MediaOrigin.LIBRIVOX, filed.origin)
        assertEquals("The Time Machine", filed.title)
        assertEquals("H. G. Wells", filed.author)
        assertEquals("Mark Nelson", filed.narrator)
        assertEquals(listOf("Chapter 1", "Chapter 2"), media.chapterList.value.map { it.title })
        assertEquals(book.files.size, MediaManifests.read("$root/audiobooks/librivox/817")?.files?.size)
    }

    /** The queue, as far as a group goes: every file "downloads" the moment it's queued, at its full size. */
    private inner class InstantDownloads : Downloads {
        var group: String? = null
        override val records = MutableStateFlow<List<DownloadRecord>>(emptyList())
        override val paused: StateFlow<Boolean> = MutableStateFlow(false)
        override val live: Flow<DownloadLive> = emptyFlow()

        override suspend fun enqueueGroup(groupId: String, specs: List<DownloadSpec>, volumeRoot: String, wifiOnly: Boolean, next: Boolean): EnqueueResult {
            group = groupId
            records.value = specs.map { spec ->
                val path = "$volumeRoot/${spec.relativePath}"
                SystemFileSystem.sink(Path(path)).buffered().use { it.write(ByteArray(spec.sizeBytes.toInt())) }
                DownloadRecord(
                    itemId = spec.itemId, hash = spec.hash, hashAlgorithm = spec.hashAlgorithm, kind = spec.kind, title = spec.title,
                    urls = spec.urls, sizeBytes = spec.sizeBytes, filePath = path, snapshotDate = spec.snapshotDate,
                    groupId = spec.groupId, wifiOnly = wifiOnly, state = DownloadState.INSTALLED, createdAt = 0L, updatedAt = 0L,
                )
            }
            return EnqueueResult.Queued
        }

        override suspend fun enqueue(item: CatalogItem, volumeRoot: String, wifiOnly: Boolean) = error("not a group")
        override suspend fun enqueue(spec: DownloadSpec, volumeRoot: String, wifiOnly: Boolean) = error("not a group")
        override suspend fun retry(itemId: String) = Unit
        override suspend fun cancel(itemId: String) = Unit
        override suspend fun remove(itemId: String) = Unit
        override suspend fun removeVersion(itemId: String, hash: String) = Unit
        override suspend fun repair(itemId: String) = Unit
        override suspend fun recordCheck(itemId: String, hash: String, verified: Boolean) = Unit
        override suspend fun setWifiOnly(wifiOnly: Boolean) = Unit
        override suspend fun recover() = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun downloadNext(itemId: String) = Unit
        override suspend fun cancelGroup(groupId: String) = Unit
        override suspend fun removeGroup(groupId: String) = Unit
        override suspend fun retryGroup(groupId: String) = Unit
    }

    private class Prefs : DownloadPrefs {
        override var wifiOnly = false
        override var volumePath: String? = null
        override var askBeforeLarge = true
        override var paused = false
        override var batchStartedAt = 0L
    }

    private class MemoryMedia : MediaLibrary {
        val trackList = MutableStateFlow<List<Track>>(emptyList())
        val bookList = MutableStateFlow<List<Audiobook>>(emptyList())
        val chapterList = MutableStateFlow<List<AudiobookChapter>>(emptyList())
        override val tracks: Flow<List<Track>> = trackList.map { all -> all.filter { it.audiobookId == null } }
        override val audiobooks: Flow<List<Audiobook>> = bookList
        override val allChapters: Flow<List<AudiobookChapter>> = chapterList
        override val albumNotes: Flow<List<AlbumNote>> = MutableStateFlow(emptyList())
        override fun chapters(audiobookId: String) = chapterList.map { all -> all.filter { it.audiobookId == audiobookId } }
        override suspend fun track(id: String) = trackList.value.firstOrNull { it.id == id }
        override suspend fun audiobook(id: String) = bookList.value.firstOrNull { it.id == id }
        override suspend fun coverPaths() = emptySet<String>()
        override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) {
            trackList.value = trackList.value.filter { it.origin != origin } + result.tracks
            bookList.value = bookList.value.filter { it.origin != origin } + result.audiobooks.map { it.book }
            chapterList.value = chapterList.value.filter { c -> result.audiobooks.none { it.book.id == c.audiobookId } } +
                result.audiobooks.flatMap { b -> b.chapters.mapIndexed { i, (trackId, c) -> AudiobookChapter(b.book.id, i, c.title, trackId, c.startMs, 0L) } }
        }
        override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) = Unit
    }

    private fun deleteTree(path: Path) {
        val meta = SystemFileSystem.metadataOrNull(path) ?: return
        if (meta.isDirectory) SystemFileSystem.list(path).forEach(::deleteTree)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
