package app.granger.audiobooks

import app.granger.FakeShelves
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.media.MediaDownloads
import app.granger.record
import app.granger.sources.ItemStatus
import app.granger.MainDispatcherRule
import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.AudiobookState
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaOrigin
import app.granger.core.library.MediaType
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.music.FakeMediaLibrary
import app.granger.player.FakePlayerHub
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import app.granger.player.bookProgress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** In-memory states and bookmarks, written as the DAO would. */
class FakeListening : AudiobookListening {
    val stateList = MutableStateFlow<List<AudiobookState>>(emptyList())
    val bookmarkList = MutableStateFlow<List<AudioBookmark>>(emptyList())

    override val states: Flow<List<AudiobookState>> = stateList
    override suspend fun state(audiobookId: String): AudiobookState? = stateList.value.firstOrNull { it.audiobookId == audiobookId }
    override suspend fun saveState(state: AudiobookState) {
        stateList.update { all -> all.filterNot { it.audiobookId == state.audiobookId } + state }
    }
    override fun bookmarks(audiobookId: String): Flow<List<AudioBookmark>> =
        bookmarkList.map { all -> all.filter { it.audiobookId == audiobookId } }
    override suspend fun updateBookmark(bookmark: AudioBookmark) {
        bookmarkList.update { all -> all.map { if (it.id == bookmark.id) bookmark else it } }
    }
    override suspend fun deleteBookmark(bookmark: AudioBookmark) { bookmarkList.update { all -> all.filterNot { it.id == bookmark.id } } }
}

class AudiobooksViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val media = FakeMediaLibrary()
    private val shelves = FakeShelves()
    private val listening = FakeListening()
    private val hub = FakePlayerHub()

    private val records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    private val removed = mutableListOf<String>()

    private fun TestScope.vm(collectionId: String? = null): AudiobooksViewModel {
        val vm = AudiobooksViewModel(
            collectionId, media, shelves, listening, hub, now = { 1_000L }, compute = main.dispatcher,
            downloads = records, remover = { removed += it },
        )
        backgroundScope.launch { vm.shelf.collect {} }
        return vm
    }

    private fun book(ref: String, title: String = ref, durationMs: Long = 100_000L, status: PackStatus = PackStatus.OK) = Audiobook(
        id = "audiobook:phone:$ref", origin = MediaOrigin.PHONE, sourceRef = ref, title = title, author = "Author",
        narrator = "Narrator", description = "", coverPath = null, durationMs = durationMs, status = status, addedAt = 0L,
    )

    /** One chapter per file: "<ref>-0", "<ref>-1", … */
    private fun chaptersOf(b: Audiobook, vararg durations: Long) = durations.mapIndexed { i, ms ->
        AudiobookChapter(b.id, i, "Chapter ${i + 1}", "track:phone:${b.sourceRef}-$i", 0L, ms)
    }

    private fun state(b: Audiobook, trackId: String, positionMs: Long, updatedAt: Long, finished: Boolean = false) =
        AudiobookState(b.id, trackId, positionMs, 1.25f, true, 0, finished, updatedAt)

    private fun add(b: Audiobook, chapters: List<AudiobookChapter>) {
        media.bookList.update { it + b }
        media.chapterList.update { it + chapters }
    }

    @Test fun shelfOrdersByLastPlayedThenUnplayedThenFinished() = runTest(main.dispatcher) {
        val older = book("older", "Zebra")
        val newer = book("newer", "Yak")
        val alpha = book("alpha", "alpha")
        val beta = book("beta", "Beta")
        val doneLately = book("done-lately", "Aardvark")
        val doneLongAgo = book("done-long-ago", "Aaa")
        for (b in listOf(older, newer, alpha, beta, doneLately, doneLongAgo)) add(b, chaptersOf(b, 100_000L))
        listening.stateList.value = listOf(
            state(older, "track:phone:older-0", 10_000L, updatedAt = 10L),
            state(newer, "track:phone:newer-0", 10_000L, updatedAt = 30L),
            state(doneLately, "track:phone:done-lately-0", 100_000L, updatedAt = 50L, finished = true),
            state(doneLongAgo, "track:phone:done-long-ago-0", 100_000L, updatedAt = 20L, finished = true),
        )

        val shelf = vm().shelf.filterNotNull().filter { it.books.size == 6 }.first()
        assertThat(shelf.title).isEqualTo(ALL_AUDIOBOOKS)
        assertThat(shelf.books.map { it.book.sourceRef })
            .containsExactly("newer", "older", "alpha", "beta", "done-lately", "done-long-ago").inOrder()
        assertThat(shelf.books.map { it.listening }).containsExactly(
            Listening.IN_PROGRESS, Listening.IN_PROGRESS, Listening.UNPLAYED, Listening.UNPLAYED,
            Listening.FINISHED, Listening.FINISHED,
        ).inOrder()

        // A collection's shelf holds only the audiobooks placed in it.
        val type = MediaType("audiobooks", "Audiobooks", true, 2)
        val row = CollectionRow(
            ShelfCollection("c-mine", "Mine", "audiobooks", userCreated = true, defaultKey = null),
            packs = emptyList(), audiobooks = listOf(older, alpha),
        )
        shelves.shelfList.value = listOf(Shelf(type, listOf(row)))
        val scoped = vm("c-mine").shelf.filterNotNull().filter { it.books.isNotEmpty() }.first()
        assertThat(scoped.title).isEqualTo("Mine")
        assertThat(scoped.books.map { it.book.sourceRef }).containsExactly("older", "alpha").inOrder()
    }

    @Test fun progressIsChapterOffsetPlusPositionOverDuration() = runTest(main.dispatcher) {
        // File 0 holds chapters 0 (0–60 s) and 1 (60–100 s); file 1 holds chapter 2 (50 s). 150 s in all.
        val b = book("two-files", durationMs = 150_000L)
        val f0 = "track:phone:two-files-0"
        val f1 = "track:phone:two-files-1"
        val chapters = listOf(
            AudiobookChapter(b.id, 0, "One", f0, 0L, 60_000L),
            AudiobookChapter(b.id, 1, "Two", f0, 60_000L, 40_000L),
            AudiobookChapter(b.id, 2, "Three", f1, 0L, 50_000L),
        )
        add(b, chapters)
        listening.stateList.value = listOf(state(b, f0, 80_000L, updatedAt = 5L))   // 20 s into chapter "Two"
        val vm = vm()

        val inFirstFile = vm.shelf.filterNotNull().map { it.books.singleOrNull() }.filterNotNull().first()
        assertThat(inFirstFile.progress.positionMs).isEqualTo(80_000L)
        assertThat(inFirstFile.progress.durationMs).isEqualTo(150_000L)
        // The same arithmetic the full-screen player shows for chapter "Two" at 20 s.
        assertThat(inFirstFile.progress).isEqualTo(bookProgress(chapters, chapters[1], 20_000L, b.durationMs))

        listening.stateList.value = listOf(state(b, f1, 10_000L, updatedAt = 6L))   // 10 s into chapter "Three"
        val inSecondFile = vm.shelf.filterNotNull().map { it.books.single() }.filter { it.progress.positionMs != 80_000L }.first()
        assertThat(inSecondFile.progress.positionMs).isEqualTo(110_000L)
        assertThat(inSecondFile.fraction).isWithin(0.0001f).of(110f / 150f)

        val page = vm.book(b.id).filterNotNull().first()
        assertThat(page.progress.positionMs).isEqualTo(110_000L)
        assertThat(page.currentChapter).isEqualTo(2)
        assertThat(page.playLabel).isEqualTo("Resume")
    }

    @Test fun theBookInThePlayerPausesAndCarriesOnInPlace() = runTest(main.dispatcher) {
        val b = book("loaded", durationMs = 150_000L)
        val chapters = chaptersOf(b, 100_000L, 50_000L)
        add(b, chapters)
        listening.stateList.value = listOf(state(b, "track:phone:loaded-0", 20_000L, updatedAt = 5L))
        val playingBook = PlayerUi(NowPlaying.Book(b, chapters[0], 2, 1f, false, 0), playing = true, 30_000L, 100_000L, null)
        hub.ui.value = playingBook
        val vm = vm()

        assertThat(vm.book(b.id).filterNotNull().first().playLabel).isEqualTo("Pause")
        vm.play(b.id)
        advanceUntilIdle()
        // No reload from the saved position (20 s, behind the player's 30 s): only a pause.
        assertThat(hub.calls).containsExactly("playPause")

        hub.ui.value = playingBook.copy(playing = false)
        assertThat(vm.book(b.id).filterNotNull().first { !it.playing }.playLabel).isEqualTo("Resume")
        vm.play(b.id)
        advanceUntilIdle()
        assertThat(hub.calls).containsExactly("playPause", "playPause")
        assertThat(hub.bookPlays).isEmpty()

        // Not in the player: it loads from its saved position, as before.
        hub.ui.value = PlayerUi.IDLE
        vm.play(b.id)
        advanceUntilIdle()
        assertThat(hub.bookPlays.single()).isEqualTo(b.id to null)
    }

    @Test fun markFinishedThenResumeStartsFromBeginning() = runTest(main.dispatcher) {
        val b = book("finish-me", durationMs = 150_000L)
        add(b, chaptersOf(b, 100_000L, 50_000L))
        listening.stateList.value = listOf(state(b, "track:phone:finish-me-1", 20_000L, updatedAt = 5L))
        val vm = vm()
        vm.book(b.id).filterNotNull().first()

        vm.setFinished(b.id, true)
        advanceUntilIdle()
        val marked = listening.state(b.id)!!
        assertThat(marked.finished).isTrue()
        // The row is kept, position and settings included.
        assertThat(marked.trackId).isEqualTo("track:phone:finish-me-1")
        assertThat(marked.positionMs).isEqualTo(20_000L)
        assertThat(marked.speed).isEqualTo(1.25f)
        // A loaded copy of the book in the player adopts it, so its next save doesn't undo the mark.
        assertThat(hub.calls).contains("setFinished:${b.id}=true")

        val page = vm.book(b.id).filterNotNull().first { it.listening == Listening.FINISHED }
        assertThat(page.fraction).isEqualTo(1f)
        assertThat(page.playLabel).isEqualTo("Play from start")

        vm.play(b.id)
        advanceUntilIdle()
        val (id, at) = hub.bookPlays.single()
        assertThat(id).isEqualTo(b.id)
        assertThat(at!!.trackId).isEqualTo("track:phone:finish-me-0")
        assertThat(at.positionMs).isEqualTo(0L)
        assertThat(listening.state(b.id)!!.finished).isTrue()   // the player un-finishes it once it plays
    }

    @Test fun markUnfinishedRewindsOnlyABookLeftAtItsEnd() = runTest(main.dispatcher) {
        val atEnd = book("at-end", durationMs = 150_000L)
        val midway = book("midway", durationMs = 150_000L)
        add(atEnd, chaptersOf(atEnd, 100_000L, 50_000L))
        add(midway, chaptersOf(midway, 100_000L, 50_000L))
        listening.stateList.value = listOf(
            state(atEnd, "track:phone:at-end-1", 50_000L, updatedAt = 5L, finished = true),
            state(midway, "track:phone:midway-0", 40_000L, updatedAt = 5L, finished = true),
        )
        val vm = vm()

        vm.setFinished(atEnd.id, false)
        vm.setFinished(midway.id, false)
        advanceUntilIdle()
        val rewound = listening.state(atEnd.id)!!
        assertThat(rewound.finished).isFalse()
        assertThat(rewound.trackId).isEqualTo("track:phone:at-end-0")
        assertThat(rewound.positionMs).isEqualTo(0L)
        val kept = listening.state(midway.id)!!
        assertThat(kept.finished).isFalse()
        assertThat(kept.trackId).isEqualTo("track:phone:midway-0")
        assertThat(kept.positionMs).isEqualTo(40_000L)
        assertThat(hub.calls).containsAtLeast("setFinished:${atEnd.id}=false", "setFinished:${midway.id}=false")
    }

    @Test fun chapterAndBookmarkTapsStartThereInOneCall() = runTest(main.dispatcher) {
        val b = book("taps", durationMs = 150_000L)
        val chapters = chaptersOf(b, 100_000L, 50_000L)
        add(b, chapters)
        val mark = AudioBookmark(7L, b.id, "track:phone:taps-0", 42_000L, "Here", "", 3L)
        listening.bookmarkList.value = listOf(mark)
        val vm = vm()

        vm.playChapter(b.id, chapters[1])
        vm.playBookmark(mark)
        advanceUntilIdle()
        assertThat(hub.bookPlays.map { it.first }).containsExactly(b.id, b.id)
        assertThat(hub.bookPlays[0].second!!.trackId).isEqualTo("track:phone:taps-1")
        assertThat(hub.bookPlays[0].second!!.positionMs).isEqualTo(0L)
        assertThat(hub.bookPlays[1].second).isEqualTo(mark)
        assertThat(hub.calls.none { it.startsWith("seekToChapter") }).isTrue()

        vm.editNote(mark, "  Gandalf arrives  ")
        advanceUntilIdle()
        assertThat(listening.bookmarkList.value.single().note).isEqualTo("Gandalf arrives")
        vm.deleteBookmark(listening.bookmarkList.value.single())
        advanceUntilIdle()
        assertThat(listening.bookmarkList.value).isEmpty()
    }

    @Test fun missingBookShowsMissingAndCannotPlay() = runTest(main.dispatcher) {
        val b = book("gone", durationMs = 150_000L, status = PackStatus.MISSING)
        val chapters = chaptersOf(b, 100_000L, 50_000L)
        add(b, chapters)
        listening.stateList.value = listOf(state(b, "track:phone:gone-1", 25_000L, updatedAt = 5L))
        val mark = AudioBookmark(1L, b.id, "track:phone:gone-0", 10_000L, "Kept", "a note", 2L)
        listening.bookmarkList.value = listOf(mark)
        val vm = vm()

        val onShelf = vm.shelf.filterNotNull().map { it.books.singleOrNull() }.filterNotNull().first()
        assertThat(onShelf.missing).isTrue()
        assertThat(onShelf.progress.positionMs).isEqualTo(125_000L)

        val page = vm.book(b.id).filterNotNull().first { it.bookmarks.isNotEmpty() }
        assertThat(page.missing).isTrue()
        assertThat(page.playable).isFalse()
        assertThat(page.playLabel).isEqualTo("Missing")
        assertThat(page.bookmarks).containsExactly(mark)
        assertThat(page.progress.positionMs).isEqualTo(125_000L)

        vm.play(b.id)
        vm.playChapter(b.id, chapters[0])
        vm.playBookmark(mark)
        advanceUntilIdle()
        assertThat(hub.bookPlays).isEmpty()
    }

    // --- Downloaded books (Plan M2, Task 10) ---

    private val group = "media:librivox:817"

    private fun librivoxBook() = Audiobook(
        id = "audiobook:librivox:817", origin = MediaOrigin.LIBRIVOX, sourceRef = "817", title = "Time Machine", author = "H. G. Wells",
        narrator = "", description = "", coverPath = null, durationMs = 100_000L, status = PackStatus.OK, addedAt = 0L,
    )

    /** File [n] of the 13-chapter book (the cover is 000), in [state]. */
    private fun file(n: Int, state: DownloadState) = record(
        MediaDownloads.fileItemId(group, n), state, kind = ItemKind.AUDIO,
        filePath = "/nowhere/audiobooks/librivox/817/%02d - Chapter.mp3".format(n), sizeBytes = 100,
        bytesDone = if (state == DownloadState.INSTALLED) 100 else 10, title = "Time Machine",
    ).copy(groupId = group)

    @Test fun downloadingBookShowsChaptersSoFar() = runTest(main.dispatcher) {
        val b = librivoxBook()
        add(b, (0 until 3).map { AudiobookChapter(b.id, it, "Chapter ${it + 1}", "track:librivox:817:$it", 0L, 10_000L) })
        records.value = (1..13).map { n ->
            file(n, when { n <= 3 -> DownloadState.INSTALLED; n == 4 -> DownloadState.RUNNING; else -> DownloadState.QUEUED })
        }
        val page = vm().book(b.id).filterNotNull().first()
        assertThat(page.download).isInstanceOf(ItemStatus.Downloading::class.java)
        assertThat(page.chapters).hasSize(3)
        assertThat(page.filesDone).isEqualTo(3)
        assertThat(page.filesTotal).isEqualTo(13)
        assertThat(page.onRemoveDownload).isNotNull()
    }

    @Test fun phoneBookHasNoRemoveDownload() = runTest(main.dispatcher) {
        val b = book("mine")
        add(b, chaptersOf(b, 100_000L))
        val page = vm().book(b.id).filterNotNull().first()
        assertThat(page.download).isNull()
        assertThat(page.onRemoveDownload).isNull()
    }

    @Test fun removeDownloadCallsTheRemoverWithTheGroup() = runTest(main.dispatcher) {
        val b = librivoxBook()
        add(b, listOf(AudiobookChapter(b.id, 0, "One", "track:librivox:817:0", 0L, 10_000L)))
        // Filed from disk with no ledger rows (the ledger was cleared): still a download, shown as in.
        val page = vm().book(b.id).filterNotNull().first()
        assertThat(page.download).isEqualTo(ItemStatus.Installed)
        page.onRemoveDownload!!()
        assertThat(removed).containsExactly(group)
    }
}
