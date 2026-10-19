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
class MediaLibraryTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val library = RoomMediaLibrary(db, now = { clock })
    private val playback = db.playbackDao()

    @After fun close() = db.close()

    private fun track(
        ref: String,
        origin: MediaOrigin = MediaOrigin.PHONE,
        audiobookId: String? = null,
        title: String = ref,
        durationMs: Long = 60_000L,
        addedAt: Long = clock,
    ) = Track(
        id = "track:${origin.name.lowercase()}:$ref", origin = origin, uri = "content://media/$ref", folder = "Music",
        title = title, album = "Album", albumKey = "album", albumArtists = "Artist", artists = "Artist", composers = "",
        genres = "", disc = 1, trackNo = 1, date = "", originalDate = "", releaseType = "", sortTitle = "",
        sortAlbum = "", sortArtist = "", sortAlbumArtist = "", durationMs = durationMs, rgTrackGain = null,
        rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = null, audiobookId = audiobookId,
        status = PackStatus.OK, addedAt = addedAt,
    )

    private fun audiobook(ref: String, origin: MediaOrigin = MediaOrigin.PHONE, title: String = ref) = Audiobook(
        id = "audiobook:${origin.name.lowercase()}:$ref", origin = origin, sourceRef = ref, title = title,
        author = "Author", narrator = "", description = "", coverPath = null, durationMs = 120_000L,
        status = PackStatus.OK, addedAt = clock,
    )

    /** An audiobook of [files], one chapter per file, plus its file tracks. */
    private fun book(ref: String, files: List<String>, origin: MediaOrigin = MediaOrigin.PHONE): Pair<ScannedAudiobook, List<Track>> {
        val book = audiobook(ref, origin)
        val tracks = files.map { track(it, origin, audiobookId = book.id) }
        return ScannedAudiobook(book, tracks.map { it.id }, tracks.map { it.id to ScannedChapter(it.title, 0L) }) to tracks
    }

    private suspend fun allTracks() = db.mediaDao().tracksOf(MediaOrigin.PHONE) + db.mediaDao().tracksOf(MediaOrigin.LIBRIVOX)
    private suspend fun statusOf(trackId: String) = library.track(trackId)!!.status

    @Test fun syncInsertsThenMarksAbsentRowsMissing() = runTest {
        val (scanned, files) = book("hobbit", listOf("h1", "h2"))
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a"), track("b")) + files, listOf(scanned)))
        assertThat(library.tracks.first().map { it.id }).containsExactly("track:phone:a", "track:phone:b")
        assertThat(library.audiobooks.first().single().status).isEqualTo(PackStatus.OK)
        assertThat(library.chapters(scanned.book.id).first().map { it.trackId }).containsExactly("track:phone:h1", "track:phone:h2").inOrder()

        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a")), emptyList()))
        assertThat(statusOf("track:phone:a")).isEqualTo(PackStatus.OK)
        assertThat(statusOf("track:phone:b")).isEqualTo(PackStatus.MISSING)
        assertThat(statusOf("track:phone:h1")).isEqualTo(PackStatus.MISSING)
        assertThat(library.audiobook(scanned.book.id)!!.status).isEqualTo(PackStatus.MISSING)
        assertThat(allTracks()).hasSize(4)
        assertThat(library.chapters(scanned.book.id).first()).hasSize(2)
    }

    /** A source that couldn't be listed: its rows are unknown, so they stay exactly as they were. */
    @Test fun syncLeavesKeptRowsAsTheyAre() = runTest {
        val (scanned, files) = book("hobbit", listOf("h1", "h2"))
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a"), track("b")) + files, listOf(scanned)))
        val before = allTracks().associateBy { it.id }

        val keep = { uri: String -> uri == "content://media/b" || uri == "content://media/h1" }
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a"), track("c")), emptyList()), keep)
        assertThat(library.track("track:phone:b")).isEqualTo(before.getValue("track:phone:b"))
        assertThat(library.track("track:phone:h1")).isEqualTo(before.getValue("track:phone:h1"))
        assertThat(statusOf("track:phone:h2")).isEqualTo(PackStatus.MISSING)
        assertThat(statusOf("track:phone:c")).isEqualTo(PackStatus.OK)
        // The book has a kept file, so it isn't marked missing either.
        assertThat(library.audiobook(scanned.book.id)!!.status).isEqualTo(PackStatus.OK)
        assertThat(library.chapters(scanned.book.id).first()).hasSize(2)
    }

    @Test fun missingRowComingBackIsOkWithStateKept() = runTest {
        val (scanned, files) = book("hobbit", listOf("h1"))
        val full = ScanResult(listOf(track("a")) + files, listOf(scanned))
        library.sync(MediaOrigin.PHONE, full)
        val state = AudiobookState(scanned.book.id, "track:phone:h1", 42_000L, 1.25f, true, 300, false, 5L)
        playback.upsertAudiobookState(state)
        val markId = playback.insertBookmark(AudioBookmark(0, scanned.book.id, "track:phone:h1", 40_000L, "Riddles", "", 6L))
        val listId = playback.insertPlaylist(Playlist(0, "Mix", 7L, 7L))
        playback.insertEntries(listOf(PlaylistEntry(listId, 0, "track:phone:a")))

        library.sync(MediaOrigin.PHONE, ScanResult(emptyList(), emptyList()))
        assertThat(statusOf("track:phone:a")).isEqualTo(PackStatus.MISSING)
        assertThat(library.audiobook(scanned.book.id)!!.status).isEqualTo(PackStatus.MISSING)

        library.sync(MediaOrigin.PHONE, full)
        assertThat(statusOf("track:phone:a")).isEqualTo(PackStatus.OK)
        assertThat(statusOf("track:phone:h1")).isEqualTo(PackStatus.OK)
        assertThat(library.audiobook(scanned.book.id)!!.status).isEqualTo(PackStatus.OK)
        assertThat(playback.audiobookState(scanned.book.id)).isEqualTo(state)
        assertThat(playback.observeBookmarks(scanned.book.id).first().map { it.id }).containsExactly(markId)
        assertThat(playback.entries(listId).map { it.trackId }).containsExactly("track:phone:a")
    }

    @Test fun syncNeverTouchesOtherOrigins() = runTest {
        val (scanned, files) = book("odyssey", listOf("o1"), MediaOrigin.LIBRIVOX)
        library.sync(MediaOrigin.LIBRIVOX, ScanResult(listOf(track("x", MediaOrigin.LIBRIVOX)) + files, listOf(scanned)))
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a")), emptyList()))
        library.sync(MediaOrigin.PHONE, ScanResult(emptyList(), emptyList()))
        library.markAllMissing(MediaOrigin.PHONE)

        assertThat(statusOf("track:librivox:x")).isEqualTo(PackStatus.OK)
        assertThat(statusOf("track:librivox:o1")).isEqualTo(PackStatus.OK)
        assertThat(library.audiobook(scanned.book.id)!!.status).isEqualTo(PackStatus.OK)
        assertThat(library.chapters(scanned.book.id).first()).hasSize(1)
        assertThat(statusOf("track:phone:a")).isEqualTo(PackStatus.MISSING)
    }

    @Test fun markAllMissingKeepsEverything() = runTest {
        val (scanned, files) = book("hobbit", listOf("h1", "h2"))
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a")) + files, listOf(scanned)))
        playback.upsertAudiobookState(AudiobookState(scanned.book.id, "track:phone:h2", 1_000L, 1f, false, 0, false, 5L))
        playback.insertBookmark(AudioBookmark(0, scanned.book.id, "track:phone:h1", 500L, "Start", "", 6L))

        library.markAllMissing(MediaOrigin.PHONE)

        assertThat(allTracks().map { it.status }).containsExactly(PackStatus.MISSING, PackStatus.MISSING, PackStatus.MISSING)
        assertThat(library.audiobooks.first().map { it.status }).containsExactly(PackStatus.MISSING)
        assertThat(library.chapters(scanned.book.id).first()).hasSize(2)
        assertThat(playback.audiobookState(scanned.book.id)!!.positionMs).isEqualTo(1_000L)
        assertThat(playback.observeBookmarks(scanned.book.id).first()).hasSize(1)
    }

    @Test fun tracksFlowExcludesAudiobookFiles() = runTest {
        val (scanned, files) = book("hobbit", listOf("h1"))
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("b", title = "beta"), track("a", title = "Alpha")) + files, listOf(scanned)))
        assertThat(library.tracks.first().map { it.title }).containsExactly("Alpha", "beta").inOrder()
        assertThat(library.track("track:phone:h1")!!.audiobookId).isEqualTo(scanned.book.id)
    }

    @Test fun resyncUpdatesChangedRowsAndKeepsAddedAt() = runTest {
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a", title = "Old", addedAt = 1_000L)), emptyList()))
        clock = 9_000L
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(track("a", title = "New", addedAt = 9_000L)), emptyList()))
        val a = library.track("track:phone:a")!!
        assertThat(a.title).isEqualTo("New")
        assertThat(a.addedAt).isEqualTo(1_000L)
    }

    @Test fun audiobookResyncReplacesChaptersAndKeepsState() = runTest {
        val book = audiobook("hobbit")
        val f1 = track("h1", audiobookId = book.id, durationMs = 100_000L)
        val f2 = track("h2", audiobookId = book.id, durationMs = 50_000L)
        library.sync(MediaOrigin.PHONE, ScanResult(listOf(f1, f2), listOf(ScannedAudiobook(book, listOf(f1.id, f2.id),
            listOf(f1.id to ScannedChapter("Part one", 0L), f2.id to ScannedChapter("Part two", 0L))))))
        val state = AudiobookState(book.id, f2.id, 20_000L, 1f, false, 0, false, 5L)
        playback.upsertAudiobookState(state)

        library.sync(MediaOrigin.PHONE, ScanResult(listOf(f1, f2), listOf(ScannedAudiobook(book.copy(title = "The Hobbit"), listOf(f1.id, f2.id),
            listOf(f1.id to ScannedChapter("Bag End", 0L), f1.id to ScannedChapter("Trolls", 40_000L), f2.id to ScannedChapter("Riddles", 0L))))))

        assertThat(library.chapters(book.id).first()).containsExactly(
            AudiobookChapter(book.id, 0, "Bag End", f1.id, 0L, 40_000L),
            AudiobookChapter(book.id, 1, "Trolls", f1.id, 40_000L, 60_000L),
            AudiobookChapter(book.id, 2, "Riddles", f2.id, 0L, 50_000L),
        ).inOrder()
        assertThat(library.audiobook(book.id)!!.title).isEqualTo("The Hobbit")
        assertThat(playback.audiobookState(book.id)).isEqualTo(state)
    }
}
