package app.granger.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.FakeBookshelf
import app.granger.FakeLibrary
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import app.granger.core.library.Albums
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.RoomShelves
import app.granger.core.media.DownloadedMediaScanner
import app.granger.core.media.FileTags
import app.granger.core.media.MediaManifests
import app.granger.core.media.TagReader
import app.granger.core.taglib.RawTags
import app.granger.record
import app.granger.sources.ItemStatus
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.flow.first
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
class MediaDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .addCallback(LibraryDatabase.SeedCallback)
        .allowMainThreadQueries()
        .build()
    private val media = RoomMediaLibrary(db)

    @After fun close() = db.close()

    private val holstGroup = "media:archive-org:${MediaFixtures.HOLST}"

    @Test fun holstFixtureDownloadsAsOneSevenTrackAlbum() = runTest {
        val m = MediaFixtures.holst()
        val specs = MediaDownloads.specs(m)
        assertThat(specs).hasSize(8)
        assertThat(specs.map { it.groupId }.toSet()).containsExactly(holstGroup)
        assertThat(specs.all { it.relativePath.startsWith("music/archive-org/${MediaFixtures.HOLST}/") }).isTrue()
        assertThat(specs.all { it.hashAlgorithm == HashAlgorithm.SHA1 }).isTrue()
        assertThat(specs.map { it.title }.toSet()).containsExactly("The Planets Op. 32, H. 125")
        assertThat(specs.first().relativePath).endsWith("/cover.jpg")

        // The files arrive in a temp volume; Venus alone carries an album tag, its own title.
        val root = tmp.newFolder("internal")
        val dir = File(root, MediaManifests.relativeDir(m))
        MediaManifests.write(dir, m)
        m.files.forEach { MediaFixtures.arrive(root, m, it) }
        val tags = TagReader { _, f ->
            val props = if ("Venus" in f.displayName) mapOf("ALBUM" to listOf("Venus")) else emptyMap()
            FileTags(RawTags(props, 0L, 44_100, 128, 2, null), emptyList())
        }
        DownloadedMediaScanner({ listOf(root) }, media, tags).scan()

        val albums = Albums.summaries(media.tracks.first())
        val album = albums.single()
        assertThat(album.title).isEqualTo("The Planets Op. 32, H. 125")
        assertThat(album.trackCount).isEqualTo(7)
        assertThat(album.albumArtist).isEqualTo("Gustav Holst")
        val ordered = Albums.ordered(media.tracks.first())
        assertThat(ordered.first().title).contains("Mars")
        assertThat(ordered[1].title).contains("Venus")

        // Filed under Music → Archive.org.
        val shelves = RoomShelves(db, FakeLibrary(), FakeBookshelf(), media).shelves.first()
        val music = shelves.single { it.mediaType.id == "music" }.collections.single()
        assertThat(music.collection.name).isEqualTo("Archive.org")
        assertThat(music.albums.map { it.title to it.trackCount }).containsExactly("The Planets Op. 32, H. 125" to 7)
    }

    @Test fun librivoxManifestKeepsSectionIdsReadersAndCover() {
        val m = MediaFixtures.timeMachine()
        assertThat(m.files).hasSize(13)
        assertThat(m.ref).isEqualTo("817")
        assertThat(m.archiveIdentifier).isEqualTo(MediaFixtures.TIME_MACHINE)
        assertThat(MediaManifests.relativeDir(m)).isEqualTo("audiobooks/librivox/817")
        assertThat(m.files.first().sectionId).isEqualTo("133434")
        assertThat(m.files.all { !it.sectionId.isNullOrBlank() && it.readers.isNotEmpty() }).isTrue()
        assertThat(m.files.map { it.trackNo }).isEqualTo((1..13).toList())
        assertThat(m.files.first().remoteName).isEqualTo("timemachinewells_01_ae_64kb.mp3")
        assertThat(m.cover!!.remoteName).isEqualTo("Time_Machine_1112.jpg")
        assertThat(m.cover!!.name).isEqualTo("cover.jpg")
        assertThat(m.creators).isNotEmpty()
        assertThat(m.readers).isNotEmpty()
    }

    @Test fun audioSpecsAreGrouped() {
        val m = MediaFixtures.timeMachine()
        val specs = MediaDownloads.specs(m)
        assertThat(specs).hasSize(14)
        assertThat(specs.all { it.kind == ItemKind.AUDIO && it.groupId == "media:librivox:817" && !it.capped }).isTrue()
        assertThat(specs.map { it.itemId }).isEqualTo((0..13).map { "media:librivox:817/%03d".format(it) })
        assertThat(specs.none { it.itemId.startsWith("archive-org:") }).isTrue()
        assertThat(specs[1].relativePath).isEqualTo("audiobooks/librivox/817/${m.files[0].name}")
        assertThat(specs[1].urls.single()).isEqualTo(m.files[0].url)
        assertThat(MediaDownloads.refOf("media:librivox:817", "librivox")).isEqualTo("817")
        assertThat(MediaDownloads.refOf("media:librivox:817", "archive-org")).isNull()
        assertThat(MediaDownloads.fileItemId("media:librivox:817", 3)).isEqualTo("media:librivox:817/003")
    }

    @Test fun statusSaysPartialWhenSomeFilesAreIn() {
        val group = "media:librivox:817"
        fun file(n: Int, state: DownloadState, bytesDone: Long = 0) = record(
            "$group/%03d".format(n), state, kind = ItemKind.AUDIO, sizeBytes = 100, bytesDone = bytesDone,
            filePath = "/v/audiobooks/librivox/817/%02d - Chapter.mp3".format(n),
        ).copy(groupId = group)
        val cover = record("$group/000", DownloadState.INSTALLED, kind = ItemKind.AUDIO, sizeBytes = 100,
            filePath = "/v/audiobooks/librivox/817/cover.jpg").copy(groupId = group)
        val five = listOf(cover) + (1..5).map { file(it, DownloadState.INSTALLED) }
        assertThat(MediaDownloads.statusOf(group, 13, five)).isEqualTo(ItemStatus.Partial(5, 13))

        val running = five + file(6, DownloadState.RUNNING, bytesDone = 50)
        assertThat(MediaDownloads.statusOf(group, 13, running)).isEqualTo(ItemStatus.Downloading(650f / 700f))

        // Pixel 6: Wi-Fi lost mid-file requeues it with its bytes kept; the page said "Downloading" throughout.
        val stopped = five + file(6, DownloadState.QUEUED, bytesDone = 50).copy(wifiOnly = true)
        assertThat(MediaDownloads.statusOf(group, 13, stopped)).isEqualTo(ItemStatus.Queued(wifiOnly = true))
        // The rest of the group is queued too, in any order (Pixel 6: file 4 listed first, so it still said "Downloading").
        val behind = five + (7..13).map { file(it, DownloadState.QUEUED).copy(wifiOnly = true) } + file(6, DownloadState.QUEUED, bytesDone = 50).copy(wifiOnly = true)
        assertThat(MediaDownloads.statusOf(group, 13, behind)).isEqualTo(ItemStatus.Queued(wifiOnly = true))
        val retrying = five + file(6, DownloadState.QUEUED).copy(wifiOnly = true, error = "a.test answered 503")
        assertThat(MediaDownloads.statusOf(group, 13, retrying)).isEqualTo(ItemStatus.Queued(wifiOnly = true, lastError = "a.test answered 503"))
        // The next file, queued between two: still downloading, no "Waiting" flash.
        val between = five + file(6, DownloadState.QUEUED).copy(wifiOnly = true)
        assertThat(MediaDownloads.statusOf(group, 13, between)).isEqualTo(ItemStatus.Downloading(600f / 700f))
        // Nothing in yet: queued.
        val fresh = listOf(file(1, DownloadState.QUEUED).copy(wifiOnly = true))
        assertThat(MediaDownloads.statusOf(group, 13, fresh)).isEqualTo(ItemStatus.Queued(wifiOnly = true))

        val failed = five + file(6, DownloadState.FAILED).copy(error = "Checksum mismatch")
        assertThat(MediaDownloads.statusOf(group, 13, failed)).isEqualTo(ItemStatus.Failed("Checksum mismatch"))

        val all = listOf(cover) + (1..13).map { file(it, DownloadState.INSTALLED) }
        assertThat(MediaDownloads.statusOf(group, 13, all)).isEqualTo(ItemStatus.Installed)
        assertThat(MediaDownloads.statusOf(group, 13, emptyList())).isEqualTo(ItemStatus.NotInstalled)
        assertThat(MediaDownloads.statusOf(group, 13, listOf(cover))).isEqualTo(ItemStatus.NotInstalled)
    }
}
