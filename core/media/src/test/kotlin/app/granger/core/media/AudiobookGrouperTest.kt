package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudiobookGrouperTest {
    private fun file(name: String, folder: String) =
        FoundFile("content://media/$folder$name", name, folder, 1_000L, 0L, 60_000L, false, false)

    private fun tags(title: String, track: Int? = null, disc: Int? = null) = AudioTags(
        title = title, album = "", albumArtists = emptyList(), artists = emptyList(), composers = emptyList(),
        genres = emptyList(), disc = disc, discTotal = null, track = track, trackTotal = null, date = "", originalDate = "",
        releaseType = "", sort = SortTags("", "", "", ""), replayGain = null, durationMs = 60_000L,
    )

    @Test fun folderOfFilesIsOneBookInNaturalOrder() {
        val folder = "Audiobooks/Dune/"
        val files = listOf("10.mp3", "2.mp3", "1.mp3").map { file(it, folder) to tags(it) }
        val books = AudiobookGrouper.group(files) { emptyList() }
        assertThat(books).hasSize(1)
        assertThat(books.single().map { it.displayName }).containsExactly("1.mp3", "2.mp3", "10.mp3").inOrder()
    }

    @Test fun trackTagsComeBeforeFileNames() {
        val folder = "Audiobooks/Dune/"
        val files = listOf(
            file("a.mp3", folder) to tags("a", track = 3),
            file("b.mp3", folder) to tags("b", track = 1),
            file("c.mp3", folder) to tags("c", track = 2),
            file("d.mp3", "Audiobooks/Emma/") to tags("d"),
        )
        val books = AudiobookGrouper.group(files) { emptyList() }
        assertThat(books.map { book -> book.map { it.displayName } })
            .containsExactly(listOf("b.mp3", "c.mp3", "a.mp3"), listOf("d.mp3"))
    }

    @Test fun singleChapteredFileIsItsOwnBook() {
        val folder = "Audiobooks/"
        val chaptered = file("Dune.m4b", folder)
        val files = listOf(chaptered to tags("Dune"), file("b.mp3", folder) to tags("b"), file("a.mp3", folder) to tags("a"))
        val chapters = listOf(Chapter("One", 0), Chapter("Two", 1_000), Chapter("Three", 2_000))
        val books = AudiobookGrouper.group(files) { if (it == chaptered) chapters else emptyList() }
        assertThat(books.map { book -> book.map { it.displayName } })
            .containsExactly(listOf("Dune.m4b"), listOf("a.mp3", "b.mp3"))
    }
}
