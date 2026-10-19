package app.granger.core.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class PublicDomainTest {
    private fun dated(year: Int?) = ArchiveItem("x", "T", "", "", year, emptyList(), "", "", "", false, emptyList())

    @Test fun rollingCutoffMovesEachJanuary() {
        assertThat(PublicDomain.lastRecordingYear(LocalDate.of(2026, 12, 31))).isEqualTo(1925)
        assertThat(PublicDomain.lastRecordingYear(LocalDate.of(2027, 1, 1))).isEqualTo(1926)
        assertThat(PublicDomain.clause(ArchiveKind.MUSIC, LocalDate.of(2026, 12, 31))).isEqualTo("year:[1860 TO 1925]")
        assertThat(PublicDomain.clause(ArchiveKind.MUSIC, LocalDate.of(2027, 1, 1))).isEqualTo("year:[1860 TO 1926]")
    }

    @Test fun yearZeroAndUndatedAreHidden() {
        val in2026 = LocalDate.of(2026, 10, 19)
        val in2027 = LocalDate.of(2027, 1, 1)
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(0), in2026)).isFalse()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(null), in2026)).isFalse()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(1859), in2026)).isFalse()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(1860), in2026)).isTrue()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(1925), in2026)).isTrue()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(1926), in2026)).isFalse()
        assertThat(ArchiveOffers.isPublicDomainRecording(dated(1926), in2027)).isTrue()
    }

    @Test fun holstIsDatedByItsUploadSoItNeedsTheWaiver() {
        val holst = fixtureItem(HOLST, "item-holst-planets.json")
        assertThat(holst.year).isEqualTo(2025)
        assertThat(ArchiveOffers.isPublicDomainRecording(holst, LocalDate.of(2026, 10, 19))).isFalse()
    }

    @Test fun textsKeepTheBooksClause() {
        assertThat(PublicDomain.clause(ArchiveKind.TEXTS, LocalDate.of(2040, 1, 1))).isEqualTo(ArchiveQuery.PUBLIC_DOMAIN)
        assertThat(ArchiveQuery.PUBLIC_DOMAIN).isEqualTo("year:[* TO 1930]")
    }
}
