package app.granger.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Plan I6 Task 1: the media index on the shared fold, run on the JVM and on the iPhone. The folds are what Android's
 * `Normalizer` NFKD + `lowercase(Locale.ROOT)` gave before the move; the searches are cases from Android's
 * MediaIndexTest (core:library), with the same answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIndexCommonTest {
    @Test fun foldsAsAndroidDid() {
        val cases = mapOf(
            "Dvořák Ñandú" to "dvorak nandu",
            "Ⅻ Symphony" to "xii symphony",
            "ﬁne" to "fine",
            "Ångström" to "angstrom",
            "Straße" to "straße",
            "Ｆｕｌｌ" to "full",
            "Mötley Crüe" to "motley crue",
            // Locale.ROOT: the dotted capital I folds to a plain i, whatever the phone's language.
            "İstanbul" to "istanbul",
            "Æon" to "æon",
        )
        for ((text, folded) in cases) assertEquals(folded, MediaText.fold(text), text)
        assertEquals(listOf("op", "32", "h", "125", "i", "mars"), MediaText.words("Op. 32, H. 125_ I. Mars"))
        assertEquals(listOf("1", "2"), MediaText.words("½"))
        assertEquals("gustav holst", MediaNames.nameKey("  Gustav HOLST "))
    }

    private val media = Memory()

    private fun TestScope.index() = MediaIndex(media, backgroundScope, UnconfinedTestDispatcher(testScheduler))

    private fun planets() = listOf("Mars", "Venus", "Jupiter").mapIndexed { i, title ->
        track("p${i + 1}", album = "The Planets", albumArtist = "Gustav Holst", trackNo = i + 1, title = title)
    }

    private fun wall() = listOf(
        track("w1", album = "The Wall", albumArtist = "Pink Floyd", title = "Comfortably Numb", trackNo = 6),
        track("w2", album = "The Wall", albumArtist = "Pink Floyd", title = "Hey You", trackNo = 1),
    )

    @Test fun holstFindsTheAlbumAndTheArtist() = runTest {
        media.trackList.value = planets() + wall()
        val r = index().search("Holst")
        assertEquals(listOf(ArtistHit("Gustav Holst", 1, null)), r.artists)
        assertEquals(listOf("The Planets"), r.albums.map { it.title })
        assertEquals(setOf("Mars", "Venus", "Jupiter"), r.songs.map { it.title }.toSet())
        assertTrue(r.audiobooks.isEmpty())
    }

    @Test fun lastWordIsAPrefixOthersAreWhole() = runTest {
        media.trackList.value = planets()
        val index = index()
        assertEquals(listOf("The Planets"), index.search("the plan").albums.map { it.title })
        assertTrue(index.search("plan planets").isEmpty)
    }

    @Test fun accentsAndCaseFoldOnBothSides() = runTest {
        media.trackList.value = listOf(track("d1", album = "Symphony No. 9", albumArtist = "Antonín Dvořák", title = "Largo"))
        val index = index()
        assertEquals(listOf("Antonín Dvořák"), index.search("dvorak").artists.map { it.name })
        assertEquals(listOf("Symphony No. 9"), index.search("ANTONIN").albums.map { it.title })
    }

    @Test fun titleOutranksDescription() = runTest {
        val described = track("d1", album = "Orchestral Suites", albumArtist = "Someone")
        val titled = track("t1", album = "Saturn Rising", albumArtist = "Else")
        media.trackList.value = listOf(described, titled)
        media.noteList.value = listOf(AlbumNote(described.albumKey, "Includes Saturn, the bringer of old age."))
        assertEquals(listOf("Saturn Rising", "Orchestral Suites"), index().search("saturn").albums.map { it.title })
    }

    private fun track(ref: String, album: String, albumArtist: String, title: String = ref, trackNo: Int? = 1) = Track(
        id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = "content://media/$ref", folder = "Music",
        title = title, album = album, albumKey = Albums.key(albumArtist, album, "Music"), albumArtists = albumArtist,
        artists = albumArtist, composers = "", genres = "", disc = 1, trackNo = trackNo, date = "", originalDate = "",
        releaseType = "", sortTitle = "", sortAlbum = "", sortArtist = "", sortAlbumArtist = "", durationMs = 60_000L,
        rgTrackGain = null, rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = null,
        audiobookId = null, status = PackStatus.OK, addedAt = 0L,
    )

    private class Memory : MediaLibrary {
        val trackList = MutableStateFlow<List<Track>>(emptyList())
        val noteList = MutableStateFlow<List<AlbumNote>>(emptyList())
        private val books = MutableStateFlow<List<Audiobook>>(emptyList())
        private val chapterList = MutableStateFlow<List<AudiobookChapter>>(emptyList())
        override val tracks: Flow<List<Track>> = trackList
        override val audiobooks: Flow<List<Audiobook>> = books
        override val allChapters: Flow<List<AudiobookChapter>> = chapterList
        override val albumNotes: Flow<List<AlbumNote>> = noteList
        override fun chapters(audiobookId: String) = chapterList.map { all -> all.filter { it.audiobookId == audiobookId } }
        override suspend fun track(id: String) = trackList.value.firstOrNull { it.id == id }
        override suspend fun audiobook(id: String): Audiobook? = null
        override suspend fun coverPaths() = emptySet<String>()
        override suspend fun sync(origin: MediaOrigin, result: ScanResult, keep: (uri: String) -> Boolean) = Unit
        override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) = Unit
    }
}
