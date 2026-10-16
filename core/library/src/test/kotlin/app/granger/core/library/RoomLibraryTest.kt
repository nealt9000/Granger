package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDatabase()
    private val opener = FakeZimOpener()
    private val internal by lazy { tmp.newFolder("internal", "packs") }
    private val sdCard by lazy { tmp.newFolder("sd", "packs") }
    private var dirs: () -> List<File> = { listOf(internal, sdCard) }
    private var clock = 1_000L
    private val dispatcher = StandardTestDispatcher()
    private val library = RoomLibrary(db, opener, { dirs() }, dispatcher, now = { clock })

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun zim(dir: File, name: String, uuid: String, title: String = uuid, paths: Set<String> = setOf("index")): File {
        opener.archives[name] = { FakeZimArchive(uuid, title, paths) }
        return File(dir, name).apply { writeText("zim $uuid") }
    }

    private suspend fun packs() = library.packs.first()

    @Test fun registersZimFilesFromEveryVolume() = test {
        zim(internal, "wikimed.zim", "u-med", "WikiMed")
        zim(sdCard, "classics.zim", "u-cls", "Granger Classics")
        library.refresh()
        assertThat(packs().map { it.title }).containsExactly("Granger Classics", "WikiMed").inOrder()
        val med = packs().first { it.id == "u-med" }
        assertThat(med.status).isEqualTo(PackStatus.OK)
        assertThat(med.filePath).isEqualTo(File(internal, "wikimed.zim").absolutePath)
        assertThat(med.sizeBytes).isEqualTo("zim u-med".length.toLong())
        assertThat(med.language).isEqualTo("eng")
        assertThat(med.addedAt).isEqualTo(1_000L)
    }

    @Test fun ignoresPartialDownloadsAndOtherFiles() = test {
        zim(internal, "a.zim", "u-a")
        File(internal, "b.zim.part").writeText("half")
        File(internal, "notes.txt").writeText("x")
        File(internal, "folder.zim").mkdir()
        library.refresh()
        assertThat(packs().map { it.id }).containsExactly("u-a")
    }

    @Test fun missingVolumeFoldersAreSkipped() = test {
        dirs = { listOf(internal, File(tmp.root, "unmounted/packs")) }
        zim(internal, "a.zim", "u-a")
        library.refresh()
        assertThat(packs().map { it.id }).containsExactly("u-a")
    }

    @Test fun unreadableFileIsDamagedAndOthersStillWork() = test {
        zim(internal, "good.zim", "u-good")
        File(internal, "bad.zim").writeText("garbage")
        library.refresh()
        val bad = packs().first { it.status == PackStatus.CORRUPT }
        assertThat(bad.title).isEqualTo("bad")
        assertThat(bad.id).isEqualTo("file:" + File(internal, "bad.zim").absolutePath)
        assertThat(library.archive(bad.id)).isNull()
        assertThat(library.archive("u-good")).isNotNull()
    }

    @Test fun packThatStopsOpeningKeepsItsIdAndBookmarks() = test {
        val file = zim(internal, "a.zim", "u-a")
        library.refresh()
        library.setBookmarked("u-a", "index", "Home", true)
        opener.archives.remove("a.zim")
        library.refresh()
        assertThat(packs().single().id).isEqualTo("u-a")
        assertThat(packs().single().status).isEqualTo(PackStatus.CORRUPT)
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-a")
        assertThat(file.exists()).isTrue()
    }

    @Test fun deletedFileBecomesMissingAndComesBack() = test {
        val file = zim(sdCard, "a.zim", "u-a")
        library.refresh()
        library.setBookmarked("u-a", "index", "Home", true)
        file.delete()
        library.refresh()
        assertThat(packs().single().status).isEqualTo(PackStatus.MISSING)
        assertThat(library.archive("u-a")).isNull()
        assertThat(library.bookmarks.first()).hasSize(1)

        zim(sdCard, "a.zim", "u-a")
        library.refresh()
        assertThat(packs().single().status).isEqualTo(PackStatus.OK)
        assertThat(library.archive("u-a")).isNotNull()
    }

    @Test fun sameZimInTwoFoldersIsListedOnce() = test {
        zim(internal, "a.zim", "u-a")
        zim(sdCard, "a-copy.zim", "u-a")
        opener.archives["a-copy.zim"] = { FakeZimArchive("u-a") }
        library.refresh()
        assertThat(packs()).hasSize(1)
    }

    @Test fun newSnapshotAtTheSamePathReplacesTheOldRow() = test {
        zim(internal, "wiki.zim", "u-old")
        library.refresh()
        zim(internal, "wiki.zim", "u-new")
        library.refresh()
        assertThat(packs().map { it.id }).containsExactly("u-new")
    }

    @Test fun refreshKeepsAddedAt() = test {
        zim(internal, "a.zim", "u-a")
        library.refresh()
        clock = 9_000L
        library.refresh()
        assertThat(packs().single().addedAt).isEqualTo(1_000L)
    }

    @Test fun archiveIsOpenedOnceAndClosedWhenThePackGoesMissing() = test {
        val file = zim(internal, "a.zim", "u-a")
        library.refresh()
        val opensDuringRefresh = opener.opened.size
        val first = library.archive("u-a")
        assertThat(library.archive("u-a")).isSameInstanceAs(first)
        assertThat(opener.opened.size).isEqualTo(opensDuringRefresh + 1)
        // archives opened only to read metadata during refresh are closed again
        assertThat(opener.opened.take(opensDuringRefresh).all { it.closed }).isTrue()

        file.delete()
        library.refresh()
        assertThat((first as FakeZimArchive).closed).isTrue()
    }

    @Test fun packMovedToAnotherVolumeStaysOkAndKeepsAddedAt() = test {
        val file = zim(internal, "a.zim", "u-a")
        library.refresh()
        clock = 5_000L
        file.renameTo(File(sdCard, "a.zim"))
        library.refresh()
        val pack = packs().single()
        assertThat(pack.status).isEqualTo(PackStatus.OK)
        assertThat(pack.filePath).isEqualTo(File(sdCard, "a.zim").absolutePath)
        assertThat(pack.addedAt).isEqualTo(1_000L)
    }

    @Test fun archiveRefusesAFileThatIsNoLongerThatPack() = test {
        zim(internal, "wiki.zim", "u-old")
        library.refresh()
        // A new snapshot replaced the file but no refresh has run yet (e.g. process restored into the reader).
        opener.archives["wiki.zim"] = { FakeZimArchive("u-new") }
        assertThat(library.archive("u-old")).isNull()
    }

    @Test fun cachedArchiveNeverOpensAnything() = test {
        zim(internal, "a.zim", "u-a")
        library.refresh()
        val opens = opener.opened.size
        assertThat(library.cachedArchive("u-a")).isNull()
        assertThat(opener.opened.size).isEqualTo(opens)
        val opened = library.archive("u-a")
        assertThat(library.cachedArchive("u-a")).isSameInstanceAs(opened)
    }

    @Test fun archiveIsNullForUnknownPacks() = test {
        assertThat(library.archive("nope")).isNull()
    }

    @Test fun startPathIsMainPageThenLastRead() = test {
        zim(internal, "a.zim", "u-a", paths = setOf("index", "C++", "Photosynthesis"))
        library.refresh()
        assertThat(library.startPath("u-a")).isEqualTo("index")
        library.savePosition("u-a", "C++", 0.4f)
        clock = 2_000L
        library.savePosition("u-a", "Photosynthesis", 0.1f)
        assertThat(library.startPath("u-a")).isEqualTo("Photosynthesis")
    }

    @Test fun startPathFallsBackToMainPageWhenLastReadIsGone() = test {
        zim(internal, "a.zim", "u-a", paths = setOf("index"))
        library.refresh()
        library.savePosition("u-a", "Deleted_in_new_snapshot", 0.5f)
        assertThat(library.startPath("u-a")).isEqualTo("index")
    }

    @Test fun startPathIsNullForUnavailablePacks() = test {
        assertThat(library.startPath("nope")).isNull()
    }

    @Test fun savePositionClampsNaNAndRange() = test {
        library.savePosition("p", "a", Float.NaN)
        library.savePosition("p", "b", 1.7f)
        library.savePosition("p", "c", -0.2f)
        library.savePosition("p", "d", 0.25f)
        assertThat(library.position("p", "a")).isEqualTo(0f)
        assertThat(library.position("p", "b")).isEqualTo(1f)
        assertThat(library.position("p", "c")).isEqualTo(0f)
        assertThat(library.position("p", "d")).isEqualTo(0.25f)
        assertThat(library.position("p", "never")).isNull()
    }

    @Test fun bookmarksToggle() = test {
        library.setBookmarked("p", "C++", "C++", true)
        assertThat(library.isBookmarked("p", "C++").first()).isTrue()
        library.setBookmarked("p", "C++", "C++", true) // twice is still one bookmark
        assertThat(library.bookmarks.first()).hasSize(1)
        library.setBookmarked("p", "C++", "C++", false)
        assertThat(library.isBookmarked("p", "C++").first()).isFalse()
    }
}
