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

    private fun zim(
        dir: File,
        fileName: String,
        uuid: String,
        title: String = uuid,
        paths: Set<String> = setOf("index"),
        name: String = uuid,
    ): File {
        opener.archives[fileName] = { FakeZimArchive(uuid, title, paths, name = name) }
        return File(dir, fileName).apply { writeText("zim $uuid") }
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

    @Test fun aFileReplacedAtTheSamePathIsOpenedAgain() = test {
        val file = zim(internal, "a.zim", "u-a")
        zim(internal, "b.zim", "u-b")
        library.refresh()
        val old = library.archive("u-a") as FakeZimArchive
        val other = library.archive("u-b") as FakeZimArchive
        library.fileReplaced(file.path)
        assertThat(old.closed).isTrue()
        val reopened = library.archive("u-a")
        assertThat(reopened).isNotSameInstanceAs(old)
        assertThat(reopened).isNotNull()
        assertThat(other.closed).isFalse()
        assertThat(library.archive("u-b")).isSameInstanceAs(other)
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

    @Test fun recordsTheZimName() = test {
        zim(internal, "a.zim", "u-a", name = "wikipedia_en_top")
        library.refresh()
        assertThat(packs().single().name).isEqualTo("wikipedia_en_top")
    }

    @Test fun aGutenbergPackIsNamedForItsSubject() = test {
        opener.archives["m.zim"] = {
            FakeZimArchive("u-m", "Project Gutenberg Library", name = "gutenberg_en_lcc-m", description = "music and books on music")
        }
        File(internal, "m.zim").writeText("zim u-m")
        opener.archives["all.zim"] = {
            FakeZimArchive("u-all", "Project Gutenberg Library", name = "gutenberg_en_all", description = "All books in English")
        }
        File(internal, "all.zim").writeText("zim u-all")
        zim(internal, "other.zim", "u-o", "Project Gutenberg Library", name = "wikipedia_en_top")
        library.refresh()
        assertThat(packs().associate { it.name to it.title }).containsExactly(
            "gutenberg_en_lcc-m", "Music and books on music · Project Gutenberg",
            "gutenberg_en_all", "Every English book · Project Gutenberg",
            // Only a Gutenberg ZIM name is renamed.
            "wikipedia_en_top", "Project Gutenberg Library",
        )
    }

    /** Plan KB Decision 3: a pack's scraper is read from its file, and a row from before version 15 gets it on refresh. */
    @Test fun aPacksScraperIsReadAndBackfilledOnRefresh() = test {
        opener.archives["water.zim"] = {
            FakeZimArchive("u-w", "Water", name = "zimgit-water_en", scraper = "nautiluszim 1.1.1")
        }
        File(internal, "water.zim").writeText("zim u-w")
        library.refresh()
        val pack = packs().single()
        assertThat(pack.scraper).isEqualTo("nautiluszim 1.1.1")
        assertThat(pack.bookSource).isEqualTo(BookOrigin.NAUTILUS)
        // A row written before the column (scraper null) is rewritten by the next refresh, keeping its addedAt.
        db.packDao().upsert(pack.copy(scraper = null, addedAt = 7L))
        assertThat(packs().single().bookSource).isNull()
        library.refresh()
        assertThat(packs().single().scraper).isEqualTo("nautiluszim 1.1.1")
        assertThat(packs().single().addedAt).isEqualTo(7L)
    }

    @Test fun updatedSnapshotKeepsBookmarksPositionsAndAddedAt() = test {
        val old = zim(internal, "top_2026-08.zim", "u-old", name = "wikipedia_en_top", paths = setOf("index", "C++"))
        library.refresh()
        library.setBookmarked("u-old", "C++", "C++", true)
        library.savePosition("u-old", "C++", 0.5f)
        clock = 9_000L

        zim(internal, "top_2026-09.zim", "u-new", name = "wikipedia_en_top", paths = setOf("index", "C++"))
        old.delete()
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-new")
        assertThat(packs().single().status).isEqualTo(PackStatus.OK)
        assertThat(packs().single().addedAt).isEqualTo(1_000L)
        assertThat(library.bookmarks.first().map { it.packId to it.path }).containsExactly("u-new" to "C++")
        assertThat(library.position("u-new", "C++")).isEqualTo(0.5f)
        assertThat(library.position("u-old", "C++")).isNull()
        assertThat(library.startPath("u-new")).isEqualTo("C++")
    }

    @Test fun newSnapshotInheritsTabsAndHistory() = test {
        val old = zim(internal, "top_2026-08.zim", "u-old", name = "wikipedia_en_top")
        library.refresh()
        val sessions = RoomReadingSessions(db) { clock }
        val tabId = sessions.openTab("u-old", "index", "Home")

        zim(internal, "top_2026-09.zim", "u-new", name = "wikipedia_en_top")
        old.delete()
        library.refresh()

        assertThat(sessions.tab(tabId)?.packId).isEqualTo("u-new")
        assertThat(sessions.history.first().map { it.packId }).containsExactly("u-new")
    }

    @Test fun removingAPackDeletesItsTabsAndHistory() = test {
        zim(internal, "a.zim", "u-a")
        library.refresh()
        val sessions = RoomReadingSessions(db) { clock }
        val tabId = sessions.openTab("u-a", "index", "Home")
        assertThat(library.remove("u-a")).isTrue()
        assertThat(sessions.tab(tabId)).isNull()
        assertThat(sessions.history.first()).isEmpty()
    }

    @Test fun snapshotReplacedAtTheSamePathKeepsBookmarks() = test {
        zim(internal, "top.zim", "u-old", name = "wikipedia_en_top")
        library.refresh()
        library.setBookmarked("u-old", "index", "Home", true)

        zim(internal, "top.zim", "u-new", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-new")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-new")
    }

    @Test fun bothSnapshotsPresentStayApart() = test {
        zim(internal, "top_2026-08.zim", "u-old", name = "wikipedia_en_top")
        library.refresh()
        library.setBookmarked("u-old", "index", "Home", true)

        zim(sdCard, "top_2026-09.zim", "u-new", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-old", "u-new")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-old")
    }

    @Test fun differentNamesAreNotMerged() = test {
        val old = zim(internal, "med.zim", "u-med", name = "wikipedia_en_medicine")
        library.refresh()
        library.setBookmarked("u-med", "index", "Home", true)
        old.delete()
        zim(internal, "top.zim", "u-top", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().first { it.id == "u-med" }.status).isEqualTo(PackStatus.MISSING)
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-med")
    }

    @Test fun blankNamesAreNeverMerged() = test {
        val old = zim(internal, "a.zim", "u-a", name = "")
        library.refresh()
        old.delete()
        zim(internal, "b.zim", "u-b", name = "")
        library.refresh()
        assertThat(packs().map { it.id to it.status })
            .containsExactly("u-a" to PackStatus.MISSING, "u-b" to PackStatus.OK)
    }

    @Test fun movingKeepsOneBookmarkWhenBothSnapshotsHadIt() = test {
        val old = zim(internal, "old.zim", "u-old", name = "n")
        library.refresh()
        library.setBookmarked("u-old", "index", "Old title", true)
        // A bookmark already saved against the new id (e.g. the user opened it before the old one vanished).
        library.setBookmarked("u-new", "index", "New title", true)
        old.delete()
        zim(internal, "new.zim", "u-new", name = "n")
        library.refresh()
        assertThat(library.bookmarks.first().map { it.packId to it.title }).containsExactly("u-new" to "New title")
    }

    @Test fun removeDeletesFileRowBookmarksAndPositions() = test {
        val file = zim(internal, "a.zim", "u-a")
        zim(internal, "b.zim", "u-b")
        library.refresh()
        val opened = library.archive("u-a") as FakeZimArchive
        library.setBookmarked("u-a", "index", "Home", true)
        library.setBookmarked("u-b", "index", "Home", true)
        library.savePosition("u-a", "index", 0.3f)

        assertThat(library.remove("u-a")).isTrue()

        assertThat(file.exists()).isFalse()
        assertThat(opened.closed).isTrue()
        assertThat(packs().map { it.id }).containsExactly("u-b")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-b")
        assertThat(library.position("u-a", "index")).isNull()
        library.refresh()
        assertThat(packs().map { it.id }).containsExactly("u-b")
    }

    @Test fun removingAPackRunsTheRemovedHook() = test {
        val gone = mutableListOf<String>()
        val hooked = RoomLibrary(db, opener, { dirs() }, dispatcher, now = { clock }, onRemoved = { gone += it })
        zim(internal, "a.zim", "u-a")
        hooked.refresh()
        assertThat(hooked.remove("u-a")).isTrue()
        assertThat(gone).containsExactly("u-a")
    }

    @Test fun removeWorksForMissingAndUnknownPacks() = test {
        val file = zim(sdCard, "a.zim", "u-a")
        library.refresh()
        file.delete()
        library.refresh()
        assertThat(library.remove("u-a")).isTrue()
        assertThat(packs()).isEmpty()
        assertThat(library.remove("nope")).isTrue()
    }

    /** Plan KB walkthrough: Switch's new flavour takes the old one's place, keeping what was saved in it. */
    @Test fun anotherFlavourSupersedesTheOldOneAndKeepsItsMarks() = test {
        val gone = mutableListOf<String>()
        val hooked = RoomLibrary(db, opener, { dirs() }, dispatcher, now = { clock }, onRemoved = { gone += it })
        val old = zim(internal, "wikipedia_en_medicine_nopic_2026-10.zim", "u-nopic", name = "wikipedia_en_medicine")
        hooked.refresh()
        hooked.setBookmarked("u-nopic", "index", "Home", true)
        hooked.savePosition("u-nopic", "index", 0.4f)
        val sessions = RoomReadingSessions(db) { clock }
        val tabId = sessions.openTab("u-nopic", "index", "Home")
        clock = 9_000L
        zim(internal, "wikipedia_en_medicine_maxi_2026-10.zim", "u-maxi", name = "wikipedia_en_medicine")
        hooked.refresh()
        // Both flavours on the phone stay apart until the switch settles.
        assertThat(hooked.packs.first().map { it.id }).containsExactly("u-nopic", "u-maxi")

        assertThat(hooked.supersede("u-nopic", "u-maxi")).isTrue()

        assertThat(old.exists()).isFalse()
        assertThat(hooked.packs.first().map { it.id }).containsExactly("u-maxi")
        assertThat(hooked.packs.first().single().addedAt).isEqualTo(1_000L)
        assertThat(hooked.bookmarks.first().map { it.packId to it.path }).containsExactly("u-maxi" to "index")
        assertThat(hooked.position("u-maxi", "index")).isEqualTo(0.4f)
        assertThat(sessions.tab(tabId)?.packId).isEqualTo("u-maxi")
        assertThat(sessions.history.first().map { it.packId }).containsExactly("u-maxi")
        assertThat(gone).containsExactly("u-nopic")
        hooked.refresh()
        assertThat(hooked.packs.first().map { it.id }).containsExactly("u-maxi")
    }

    @Test fun supersedeLeavesTheOldPackWhenTheNewOneIsntThere() = test {
        val old = zim(internal, "a.zim", "u-a", name = "n")
        zim(internal, "other.zim", "u-other", name = "other")
        library.refresh()
        library.setBookmarked("u-a", "index", "Home", true)
        // No new pack (the download failed or was cancelled), or a different pack altogether.
        assertThat(library.supersede("u-a", "u-missing")).isFalse()
        assertThat(library.supersede("u-a", "u-other")).isFalse()
        assertThat(old.exists()).isTrue()
        assertThat(packs().map { it.id }).containsExactly("u-a", "u-other")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-a")
    }
}
