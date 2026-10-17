package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArchiveQueryTest {
    @Test fun filteredSearchAddsThePublicDomainClause() = assertThat(ArchiveQuery.build("steam engine", null, filtered = true))
        .isEqualTo("mediatype:texts AND (steam engine) AND (year:[* TO 1930] OR licenseurl:*publicdomain*)")

    @Test fun unfilteredShelfHasNoRightsClause() = assertThat(ArchiveQuery.build("", ArchiveShelf.MANUALS, filtered = false))
        .isEqualTo("mediatype:texts AND collection:manuals")

    @Test fun mostDownloadedFilteredIsJustTextsAndRights() = assertThat(ArchiveQuery.build("  ", ArchiveShelf.MOST_DOWNLOADED, filtered = true))
        .isEqualTo("mediatype:texts AND (year:[* TO 1930] OR licenseurl:*publicdomain*)")

    @Test fun unbalancedQuotesAndParensAreStripped() = assertThat(ArchiveQuery.build("\"steam (engine", null, false))
        .isEqualTo("mediatype:texts AND (steam engine)")

    @Test fun balancedQuotesSurvive() = assertThat(ArchiveQuery.build("\"steam engine\"", null, false))
        .isEqualTo("mediatype:texts AND (\"steam engine\")")

    @Test fun parenthesesInsideQuotesCannotEscapeTheGroup() =
        // Counted naively these balance, and would close the group early: `texts AND ("(" x) OR (y ")") AND …`.
        assertThat(ArchiveQuery.build("\"(\" x) OR (y \")\"", null, filtered = true))
            .isEqualTo("mediatype:texts AND (\"\" x OR y \"\") AND (year:[* TO 1930] OR licenseurl:*publicdomain*)")

    @Test fun balancedParenthesesAreRemovedToo() = assertThat(ArchiveQuery.build("steam (engine OR boiler)", null, false))
        .isEqualTo("mediatype:texts AND (steam engine OR boiler)")

    @Test fun identifiersAreValidated() {
        assertThat(ArchiveQuery.isValidIdentifier("lbg.vol.xviii.p.1.x_b7i9")).isTrue()
        for (bad in listOf("../x", ".x", "a/b", "a b", "")) assertThat(ArchiveQuery.isValidIdentifier(bad)).isFalse()
    }
}
