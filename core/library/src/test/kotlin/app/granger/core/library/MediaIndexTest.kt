package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaIndexTest {
    private val media = MemoryMedia()

    private fun TestScope.index() = MediaIndex(media, backgroundScope, UnconfinedTestDispatcher(testScheduler))

    private fun planets() = (1..3).map { i ->
        musicTrack("p$i", album = "The Planets", albumArtist = "Gustav Holst", trackNo = i, title = listOf("Mars", "Venus", "Jupiter")[i - 1])
    }

    private fun wall() = listOf(
        musicTrack("w1", album = "The Wall", albumArtist = "Pink Floyd", title = "Comfortably Numb", trackNo = 6),
        musicTrack("w2", album = "The Wall", albumArtist = "Pink Floyd", title = "Hey You", trackNo = 1),
    )

    private fun audiobook(ref: String, title: String, author: String = "Author", narrator: String = "", description: String = "", status: PackStatus = PackStatus.OK) =
        phoneAudiobook(ref, title).copy(author = author, narrator = narrator, description = description, status = status)

    @Test fun holstFindsTheAlbumAndTheArtist() = runTest {
        media.trackList.value = planets() + wall()
        val r = index().search("Holst")
        assertThat(r.artists).containsExactly(ArtistHit("Gustav Holst", 1, null))
        assertThat(r.albums.map { it.title }).containsExactly("The Planets")
        assertThat(r.albums.single().albumArtist).isEqualTo("Gustav Holst")
        // Every song of the album names its album artist.
        assertThat(r.songs.map { it.title }).containsExactly("Mars", "Venus", "Jupiter")
        assertThat(r.audiobooks).isEmpty()
    }

    @Test fun lastWordIsAPrefixOthersAreWhole() = runTest {
        media.trackList.value = planets()
        val index = index()
        assertThat(index.search("the plan").albums.map { it.title }).containsExactly("The Planets")
        assertThat(index.search("plan planets").isEmpty).isTrue()
    }

    @Test fun everyQueryWordMustMatch() = runTest {
        media.trackList.value = planets() + wall()
        assertThat(index().search("holst wall").isEmpty).isTrue()
    }

    @Test fun titleOutranksDescription() = runTest {
        val described = musicTrack("d1", album = "Orchestral Suites", albumArtist = "Someone")
        val titled = musicTrack("t1", album = "Saturn Rising", albumArtist = "Else")
        media.trackList.value = listOf(described, titled)
        media.noteList.value = listOf(AlbumNote(described.albumKey, "Includes Saturn, the bringer of old age."))
        assertThat(index().search("saturn").albums.map { it.title }).containsExactly("Saturn Rising", "Orchestral Suites").inOrder()
    }

    @Test fun songsMatchTheirOwnTitleAndAlbum() = runTest {
        media.trackList.value = planets() + wall()
        val index = index()
        assertThat(index.search("comfortably").songs.map { it.title }).containsExactly("Comfortably Numb")
        assertThat(index.search("comfortably").albums.map { it.title }).containsExactly("The Wall")
        // A song is found by its album's name too (equal scores: by title).
        assertThat(index.search("wall").songs.map { it.title }).containsExactly("Comfortably Numb", "Hey You")
    }

    @Test fun audiobooksMatchAuthorNarratorAndDescription() = runTest {
        media.bookList.value = listOf(
            audiobook("jungle", "The Jungle Book", author = "Rudyard Kipling", narrator = "Ann Reader"),
            audiobook("wicked", "Wicked", author = "Gregory Maguire", description = "The life of the Wicked Witch of the West."),
        )
        val index = index()
        assertThat(index.search("kipling").audiobooks.map { it.title }).containsExactly("The Jungle Book")
        assertThat(index.search("ann reader").audiobooks.map { it.title }).containsExactly("The Jungle Book")
        assertThat(index.search("witch").audiobooks.map { it.title }).containsExactly("Wicked")
        assertThat(index.search("witch").albums).isEmpty()
    }

    @Test fun missingItemsAreLeftOut() = runTest {
        media.trackList.value = planets().map { it.copy(status = PackStatus.MISSING) } + wall()
        media.bookList.value = listOf(audiobook("gone", "Holst: A Life", status = PackStatus.MISSING))
        val index = index()
        assertThat(index.search("holst").isEmpty).isTrue()
        assertThat(index.work("album:" + planets().first().albumKey)).isNull()
        assertThat(index.work("audiobook:phone:gone")).isNull()
    }

    @Test fun limitsApplyPerGroup() = runTest {
        media.trackList.value = (1..12).map { musicTrack("s$it", album = "Mix $it", albumArtist = "Mix Artist $it", title = "Mix song $it") }
        media.bookList.value = (1..7).map { audiobook("b$it", "Mix book $it") }
        val r = index().search("mix")
        assertThat(r.artists).hasSize(3)
        assertThat(r.albums).hasSize(5)
        assertThat(r.songs).hasSize(8)
        assertThat(r.audiobooks).hasSize(5)
        assertThat(index().search("mix", artists = 1, albums = 0, songs = 2, audiobooks = 0).let { listOf(it.artists.size, it.albums.size, it.songs.size, it.audiobooks.size) })
            .containsExactly(1, 0, 2, 0).inOrder()
    }

    @Test fun indexFollowsLibraryChanges() = runTest {
        media.trackList.value = wall()
        val index = index()
        assertThat(index.search("holst").isEmpty).isTrue()
        media.sync(MediaOrigin.PHONE, ScanResult(planets() + wall(), emptyList()))
        runCurrent()
        assertThat(index.search("holst").albums.map { it.title }).containsExactly("The Planets")
    }

    @Test fun worksGateOnWholeWords() = runTest {
        media.trackList.value = planets() + wall()
        media.bookList.value = listOf(audiobook("jungle", "The Jungle Book", author = "Rudyard Kipling"))
        val index = index()
        assertThat(index.works(listOf("holst"), limit = 5).map { it.title }).containsExactly("The Planets")
        assertThat(index.works(listOf("hol"), limit = 5)).isEmpty()
        assertThat(index.works(listOf("kipling"), limit = 5).map { it.id }).containsExactly("audiobook:phone:jungle")
        // Songs are never works; their album is.
        assertThat(index.works(listOf("Comfortably", "Numb"), limit = 5).map { it.id }).containsExactly("album:" + wall().first().albumKey)
        assertThat(index.works(listOf("the"), limit = 1)).hasSize(1)
    }

    @Test fun workCarriesPartsAndTheAlbumNote() = runTest {
        val tracks = planets().reversed().map { it.copy(composers = "Gustav Holst", genres = "Classical", date = "1926-01-01") }
        media.trackList.value = tracks
        val key = tracks.first().albumKey
        media.noteList.value = listOf(AlbumNote(key, "The London Symphony Orchestra, 1922–24."))
        media.bookList.value = listOf(audiobook("jungle", "The Jungle Book", author = "Rudyard Kipling", narrator = "Ann Reader; Bo Reader"))
        media.chapterList.value = listOf(
            AudiobookChapter("audiobook:phone:jungle", 1, "Kaa's Hunting", "t2", 0L, 1L),
            AudiobookChapter("audiobook:phone:jungle", 0, "Mowgli's Brothers", "t1", 0L, 1L),
        )
        val index = index()
        val album = index.work("album:$key")!!
        assertThat(album.isAlbum).isTrue()
        assertThat(album.title).isEqualTo("The Planets")
        assertThat(album.creator).isEqualTo("Gustav Holst")
        assertThat(album.partTitles).containsExactly("Mars", "Venus", "Jupiter").inOrder()
        assertThat(album.description).isEqualTo("The London Symphony Orchestra, 1922–24.")
        assertThat(album.composers).containsExactly("Gustav Holst")
        assertThat(album.genres).containsExactly("Classical")
        assertThat(album.year).isEqualTo("1926")
        assertThat(album.origin).isEqualTo(MediaOrigin.PHONE)
        val book = index.work("audiobook:phone:jungle")!!
        assertThat(book.isAlbum).isFalse()
        assertThat(book.creator).isEqualTo("Rudyard Kipling")
        assertThat(book.narrators).containsExactly("Ann Reader", "Bo Reader").inOrder()
        assertThat(book.partTitles).containsExactly("Mowgli's Brothers", "Kaa's Hunting").inOrder()
        assertThat(album.partIds).containsExactly(*planets().map { it.id }.toTypedArray()).inOrder()
        assertThat(book.partIds).containsExactly("chapter:0", "chapter:1").inOrder()
        assertThat(index.chapter("audiobook:phone:jungle", 1)?.title).isEqualTo("Kaa's Hunting")
        assertThat(index.chapter("audiobook:phone:jungle", 5)).isNull()
        assertThat(index.workWithPart(tracks.last().id)?.id).isEqualTo("album:$key")
        assertThat(index.workWithPart("track:phone:none")).isNull()
        // The note is searchable, at the lowest weight.
        assertThat(index.works(listOf("orchestra"), limit = 5).map { it.id }).containsExactly("album:$key")
    }
    /** A library flow that fails never leaves a caller waiting for the first snapshot. */
    @Test fun failedLibraryFlowLeavesAnEmptyIndex() = runTest {
        val failing = object : MediaLibrary by media {
            override val tracks: Flow<List<Track>> = flow { throw IllegalStateException("database closed") }
        }
        val index = MediaIndex(failing, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertThat(index.search("holst")).isEqualTo(MediaResults())
        assertThat(index.works(listOf("holst"), limit = 3)).isEmpty()
        assertThat(index.work("album:x")).isNull()
    }

    /** Both titles hold both words (equal field scores); only one holds them as a phrase, punctuation aside. */
    @Test fun titlePhraseBonusIgnoresPunctuation() = runTest {
        media.trackList.value = listOf(
            musicTrack("a", album = "Planets by Holst", albumArtist = "X"),
            musicTrack("b", album = "The Holst, Planets", albumArtist = "Y"),
        )
        // Without the bonus the tie goes by title, "Planets by Holst" first.
        assertThat(index().search("holst: planets").albums.map { it.title }).containsExactly("The Holst, Planets", "Planets by Holst").inOrder()
    }

    /** Ties go by title whatever the phone's language: in Turkish, "I" lower-cases to a dotless ı that sorts after "j". */
    @Test fun tiesSortByTitleInAnyLocale() = runTest {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            media.trackList.value = listOf(musicTrack("j", title = "Ja Song"), musicTrack("i", title = "Iz Song"))
            assertThat(index().search("song").songs.map { it.title }).containsExactly("Iz Song", "Ja Song").inOrder()
        } finally {
            Locale.setDefault(default)
        }
    }
}
