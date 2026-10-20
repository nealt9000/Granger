package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ArchiveQueryTest {
    @Test fun filteredSearchAddsThePublicDomainClause() = assertThat(ArchiveQuery.build("steam engine", null, filtered = true))
        .isEqualTo("mediatype:texts AND (steam engine) AND year:[* TO 1930]")

    @Test fun unfilteredShelfHasNoRightsClause() = assertThat(ArchiveQuery.build("", ArchiveShelf.MANUALS, filtered = false))
        .isEqualTo("mediatype:texts AND collection:manuals")

    @Test fun mostDownloadedFilteredIsJustTextsAndRights() = assertThat(ArchiveQuery.build("  ", ArchiveShelf.MOST_DOWNLOADED, filtered = true))
        .isEqualTo("mediatype:texts AND year:[* TO 1930]")

    @Test fun unbalancedQuotesAndParensAreStripped() = assertThat(ArchiveQuery.build("\"steam (engine", null, false))
        .isEqualTo("mediatype:texts AND (steam engine)")

    @Test fun balancedQuotesSurvive() = assertThat(ArchiveQuery.build("\"steam engine\"", null, false))
        .isEqualTo("mediatype:texts AND (\"steam engine\")")

    @Test fun parenthesesInsideQuotesCannotEscapeTheGroup() =
        // Counted naively these balance, and would close the group early: `texts AND ("(" x) OR (y ")") AND …`.
        assertThat(ArchiveQuery.build("\"(\" x) OR (y \")\"", null, filtered = true))
            .isEqualTo("mediatype:texts AND (\"\" x OR y \"\") AND year:[* TO 1930]")

    @Test fun balancedParenthesesAreRemovedToo() = assertThat(ArchiveQuery.build("steam (engine OR boiler)", null, false))
        .isEqualTo("mediatype:texts AND (steam engine OR boiler)")

    @Test fun identifiersAreValidated() {
        assertThat(ArchiveQuery.isValidIdentifier("lbg.vol.xviii.p.1.x_b7i9")).isTrue()
        for (bad in listOf("../x", ".x", "a/b", "a b", "")) assertThat(ArchiveQuery.isValidIdentifier(bad)).isFalse()
    }

    @Test fun filteredMusicUsesTheRollingRange() = assertThat(ArchiveQuery.build("", ArchiveShelf.MUSIC_MOST_DOWNLOADED, true, ArchiveKind.MUSIC, LocalDate.of(2026, 10, 19)))
        .isEqualTo("mediatype:audio AND collection:audio_music AND year:[1860 TO 1925]")

    @Test fun filteredMusicCutoffRollsOverOnJanuaryFirst() {
        assertThat(ArchiveQuery.build("waltz", ArchiveShelf.MUSIC_78, true, ArchiveKind.MUSIC, LocalDate.of(2026, 12, 31)))
            .isEqualTo("mediatype:audio AND collection:audio_music AND (title:(waltz) OR creator:(waltz)) AND collection:78rpm AND year:[1860 TO 1925]")
        assertThat(ArchiveQuery.build("waltz", ArchiveShelf.MUSIC_78, true, ArchiveKind.MUSIC, LocalDate.of(2027, 1, 1)))
            .isEqualTo("mediatype:audio AND collection:audio_music AND (title:(waltz) OR creator:(waltz)) AND collection:78rpm AND year:[1860 TO 1926]")
    }

    @Test fun unfilteredMusicHasNoDateClause() = assertThat(ArchiveQuery.build("", ArchiveShelf.MUSIC_CLASSICAL, false, ArchiveKind.MUSIC))
        .isEqualTo("mediatype:audio AND collection:audio_music AND subject:classical")

    // A search with the waiver accepted covers all archive.org audio (Pixel 6: "John Williams" found only 78s, "wicked audiobook"
    // found Plato), matching titles and creators only (every field, ranked by downloads, put AMSCO AP World History first).
    @Test fun spokenWordSearchIsWideAndLeavesLibriVoxOut() = assertThat(ArchiveQuery.build("wicked audiobook", null, false, ArchiveKind.SPOKEN_WORD))
        .isEqualTo(
            "mediatype:audio AND NOT collection:podcasts* AND NOT collection:librivoxaudio AND NOT collection:audio_music" +
                " AND (title:(wicked audiobook) OR creator:(wicked audiobook))",
        )

    @Test fun unfilteredMusicSearchIsWide() = assertThat(ArchiveQuery.build("john williams", null, false, ArchiveKind.MUSIC))
        .isEqualTo(
            "mediatype:audio AND NOT collection:podcasts* AND NOT collection:librivoxaudio AND NOT collection:audio_bookspoetry" +
                " AND NOT collection:*audiobooks* AND (title:(john williams) OR creator:(john williams))",
        )

    @Test fun filteredMusicSearchStaysCurated() = assertThat(ArchiveQuery.build("holst", null, true, ArchiveKind.MUSIC, LocalDate.of(2026, 10, 19)))
        .isEqualTo("mediatype:audio AND collection:audio_music AND (title:(holst) OR creator:(holst)) AND year:[1860 TO 1925]")

    @Test fun textSearchStillMatchesEveryField() = assertThat(ArchiveQuery.build("steam engine", null, false, ArchiveKind.TEXTS))
        .isEqualTo("mediatype:texts AND (steam engine)")

    @Test fun aShelfOfAnotherKindIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ArchiveQuery.build("", ArchiveShelf.MANUALS, true, ArchiveKind.MUSIC) }
        assertThrows(IllegalArgumentException::class.java) { ArchiveQuery.build("", ArchiveShelf.MUSIC_78, false, ArchiveKind.TEXTS) }
    }

    @Test fun filteredSpokenWordIsNeverSearched() {
        assertThat(ArchiveKind.SPOKEN_WORD.datedRecordings).isFalse()
        assertThat(ArchiveKind.MUSIC.datedRecordings).isTrue()
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveQuery.build("", ArchiveShelf.SPOKEN_MOST_DOWNLOADED, true, ArchiveKind.SPOKEN_WORD)
        }
        assertThrows(IllegalArgumentException::class.java) { ArchiveQuery.build("poems", null, true, ArchiveKind.SPOKEN_WORD) }
        assertThat(ArchiveQuery.build("", ArchiveShelf.SPOKEN_DRAMA, false, ArchiveKind.SPOKEN_WORD))
            .isEqualTo("mediatype:audio AND collection:audio_bookspoetry AND NOT collection:librivoxaudio AND subject:(drama OR plays)")
    }

    @Test fun shelvesBelongToTheirKind() {
        assertThat(ArchiveShelf.of(ArchiveKind.MUSIC).first()).isEqualTo(ArchiveShelf.MUSIC_MOST_DOWNLOADED)
        assertThat(ArchiveShelf.first(ArchiveKind.MUSIC)).isEqualTo(ArchiveShelf.MUSIC_MOST_DOWNLOADED)
        assertThat(ArchiveShelf.of(ArchiveKind.TEXTS))
            .containsExactly(ArchiveShelf.MOST_DOWNLOADED, ArchiveShelf.MANUALS, ArchiveShelf.SCIENCE, ArchiveShelf.LITERATURE).inOrder()
        assertThat(ArchiveShelf.of(ArchiveKind.SPOKEN_WORD))
            .containsExactly(ArchiveShelf.SPOKEN_MOST_DOWNLOADED, ArchiveShelf.SPOKEN_POETRY, ArchiveShelf.SPOKEN_DRAMA).inOrder()
        assertThat(ArchiveShelf.first(ArchiveKind.TEXTS)).isEqualTo(ArchiveShelf.MOST_DOWNLOADED)
    }

    @Test fun textsIgnoreTheDate() = assertThat(ArchiveQuery.build("x", null, true, ArchiveKind.TEXTS, LocalDate.of(2099, 1, 1)))
        .isEqualTo("mediatype:texts AND (x) AND year:[* TO 1930]")
}
