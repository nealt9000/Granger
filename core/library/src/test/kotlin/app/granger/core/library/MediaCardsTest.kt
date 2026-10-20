package app.granger.core.library

import app.granger.core.research.SourceKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaCardsTest {
    private fun album(
        partTitles: List<String> = listOf("Mars, the Bringer of War", "Venus, the Bringer of Peace"),
        description: String = "Holst's suite in seven movements.",
    ) = MediaWork(
        id = "album:gustav holst|the planets", isAlbum = true, title = "The Planets", creator = "Gustav Holst; London Symphony Orchestra",
        composers = listOf("Gustav Holst"), narrators = emptyList(), genres = listOf("Classical", "Orchestral"), year = "1918",
        partTitles = partTitles, description = description, origin = MediaOrigin.ARCHIVE_ORG, coverPath = null,
    )

    @Test fun albumCardHasTheLabelsInOrder() {
        val p = MediaCards.passage(album())
        assertThat(p.text).isEqualTo(
            """
            Album: The Planets
            Artist: Gustav Holst, London Symphony Orchestra
            Composer: Gustav Holst
            Genre: Classical, Orchestral
            Year: 1918
            Tracks: Mars, the Bringer of War; Venus, the Bringer of Peace
            About: Holst's suite in seven movements.
            In your library as: Music album
            """.trimIndent(),
        )
        assertThat(p.kind).isEqualTo(SourceKind.MEDIA)
        assertThat(p.sourceId).isEqualTo("album:gustav holst|the planets")
        assertThat(p.sourceTitle).isEqualTo("The Planets")
        assertThat(p.packTitle).isEqualTo("Your music")
        assertThat(p.location).isEmpty()
        assertThat(p.meta.author).isEqualTo("Gustav Holst, London Symphony Orchestra")
    }

    @Test fun tracksAndAboutAreCut() {
        val p = MediaCards.passage(album(partTitles = (1..100).map { "Movement number $it" }, description = "word ".repeat(300)))
        val tracks = p.text.lines().single { it.startsWith("Tracks: ") }.removePrefix("Tracks: ")
        val about = p.text.lines().single { it.startsWith("About: ") }.removePrefix("About: ")
        assertThat(tracks.length).isAtMost(401)
        assertThat(tracks).endsWith("…")
        assertThat(tracks.removeSuffix("…")).doesNotMatch(".*\\s$")
        assertThat(tracks).startsWith("Movement number 1; Movement number 2;")
        assertThat(about.length).isAtMost(601)
        assertThat(about).endsWith("word…")
        assertThat(p.text.lines().last()).isEqualTo("In your library as: Music album")
    }

    @Test fun audiobookCardSaysAudiobookAndNarrator() {
        val work = MediaWork(
            id = "audiobook:archive_org:wicked", isAlbum = false, title = "Wicked", creator = "Gregory Maguire",
            composers = emptyList(), narrators = listOf("John McDonough"), genres = emptyList(), year = "",
            partTitles = listOf("Part One", "Part Two"), description = "The life and times\nof the Wicked Witch.",
            origin = MediaOrigin.ARCHIVE_ORG, coverPath = null,
        )
        val p = MediaCards.passage(work)
        assertThat(p.text).isEqualTo(
            """
            Audiobook: Wicked
            Author: Gregory Maguire
            Narrator: John McDonough
            Tracks: Part One; Part Two
            About: The life and times of the Wicked Witch.
            In your library as: Audiobook
            """.trimIndent(),
        )
        assertThat(p.sourceId).isEqualTo("audiobook:archive_org:wicked")
        assertThat(p.packTitle).isEqualTo("Your audiobooks")
        assertThat(p.meta.author).isEqualTo("Gregory Maguire")
    }

    @Test fun unknownArtistIsLeftOff() {
        val p = MediaCards.passage(album().copy(creator = Albums.UNKNOWN_ARTIST))
        assertThat(p.text).doesNotContain("Artist:")
        assertThat(p.meta.author).isEmpty()
    }
}
