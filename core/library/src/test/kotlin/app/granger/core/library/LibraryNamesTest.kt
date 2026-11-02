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
class LibraryNamesTest {
    private val db = inMemoryDatabase()

    @After fun close() = db.close()

    @Test fun titlesLoseSubtitlesAndPeopleLoseBracketedNames() {
        assertThat(LibraryNames.shortTitle("The Republic: Book I (tr. Jowett)")).isEqualTo("The Republic")
        assertThat(LibraryNames.shortTitle("Where_there_is_no_Doctor - 2021 edition")).isEqualTo("Where there is no Doctor")
        assertThat(LibraryNames.shortTitle("Holst / The Planets")).isEqualTo("Holst")
        assertThat(LibraryNames.shortPerson("George T. (George Titus) Ferris")).isEqualTo("George T. Ferris")
    }

    @Test fun duplicatesShortNamesAndPlaceholdersAreDropped() {
        val rows = listOf(
            LibraryNameRow(true, "Peter Pan"),
            LibraryNameRow(true, "peter pan: or the boy who wouldn't grow up"),
            LibraryNameRow(false, "Various Artists"),
            LibraryNameRow(false, "Unknown artist"),
            LibraryNameRow(true, "XY"),
            LibraryNameRow(true, "1984"),
            LibraryNameRow(false, "Gustav Holst; London Symphony Orchestra"),
        )
        assertThat(LibraryNames.from(rows)).containsExactly("Peter Pan", "Gustav Holst", "London Symphony Orchestra").inOrder()
    }

    @Test fun theQueryCoversPacksBooksAudiobooksAndMusicButNotMissingFiles() = runTest {
        db.packDao().upsert(pack("wikimed", "WikiMed Medical Encyclopedia"))
        db.packDao().upsert(pack("gone", "Gone Pack", status = PackStatus.MISSING))
        db.bookDao().upsert(
            Book(
                id = "b1", origin = BookOrigin.IMPORT, sourceRef = "b1", filePath = "/books/b1.epub", title = "Meditations: Books 1-12",
                author = "Marcus Aurelius", translator = "", date = "", language = "eng", subjects = "", description = "",
                coverPath = null, sizeBytes = 10L, status = PackStatus.OK, indexedAt = null, addedAt = 1L,
            ),
        )
        db.mediaDao().upsertAudiobooks(
            listOf(
                Audiobook(
                    id = "audiobook:phone:pp", origin = MediaOrigin.PHONE, sourceRef = "pp", title = "Peter Pan", author = "J. M. Barrie",
                    narrator = "", description = "", coverPath = null, durationMs = 1L, status = PackStatus.OK, addedAt = 1L,
                ),
            ),
        )
        db.mediaDao().upsertTracks(
            listOf(
                track("mars", album = "The Planets", artists = "Gustav Holst; London Symphony Orchestra"),
                track("ch1", album = "Peter Pan (audiobook files)", artists = "Narrator Person", audiobookId = "audiobook:phone:pp"),
            ),
        )
        val names = LibraryNames.from(db.libraryNameDao().observe().first())
        assertThat(names).containsAtLeast(
            "WikiMed Medical Encyclopedia", "Meditations", "Marcus Aurelius", "Peter Pan", "J. M. Barrie",
            "The Planets", "Gustav Holst", "London Symphony Orchestra",
        )
        assertThat(names).containsNoneOf("Gone Pack", "Narrator Person")
    }

    @Test fun aNewBookIsMatchedTheSameDay() = runTest {
        val dao = db.libraryNameDao()
        assertThat(LibraryNames.from(dao.observe().first())).doesNotContain("Crito")
        db.packDao().upsert(pack("crito", "Crito"))
        assertThat(LibraryNames.from(dao.observe().first())).contains("Crito")
    }

    private fun track(ref: String, album: String, artists: String, audiobookId: String? = null) = Track(
        id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = "content://media/$ref", folder = "Music",
        title = ref, album = album, albumKey = album.lowercase(), albumArtists = artists, artists = artists, composers = "",
        genres = "", disc = 1, trackNo = 1, date = "", originalDate = "", releaseType = "", sortTitle = "",
        sortAlbum = "", sortArtist = "", sortAlbumArtist = "", durationMs = 1L, rgTrackGain = null,
        rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = null, audiobookId = audiobookId,
        status = PackStatus.OK, addedAt = 1L,
    )
}
