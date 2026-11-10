package app.granger.core.library

import app.granger.core.library.BookTitleSearch.Rank
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TitleMatchTest {
    @Test fun ranksOnFoldedWords() {
        val q = TitleMatch.key("guillain barre syndrome")
        assertThat(TitleMatch.rank("Guillain–Barré syndrome", q)).isEqualTo(Rank.EXACT)
        assertThat(TitleMatch.rank("Guillain–Barré syndrome in children", q)).isEqualTo(Rank.STARTS_WITH)
        assertThat(TitleMatch.rank("Syndromes", q)).isNull()
    }

    @Test fun distanceCountsASwapAsOne() {
        assertThat(TitleMatch.distance("kerri hiugchi", "kerri higuchi")).isEqualTo(1)
        assertThat(TitleMatch.distance("steatorreha", "steatorrhea")).isEqualTo(1)
        assertThat(TitleMatch.distance("prehistorc mines", "prehistoric mines")).isEqualTo(1)
        assertThat(TitleMatch.distance("abcdef", "uvwxyz", max = 2)).isGreaterThan(2)
    }

    @Test fun budgetGrowsWithLength() {
        assertThat(TitleMatch.budget("aesp")).isEqualTo(0)
        assertThat(TitleMatch.budget("misoula")).isEqualTo(1)
        assertThat(TitleMatch.budget("atomic orbtal")).isEqualTo(2)
    }

    @Test fun probesCutEachLongWordToItsFirstHalf() {
        assertThat(TitleMatch.probes("Atomic orbtal")).containsExactly("orbtal ato", "atomic orb", "orbtal", "atomic")
        assertThat(TitleMatch.probes("Steatorreha")).containsExactly("steat")
        assertThat(TitleMatch.probes("PCB assmbly")).containsExactly("pcb ass", "pcb")
        assertThat(TitleMatch.family("wikipedia_en_history")).isEqualTo("wikipedia_en")
    }

    @Test fun translatedCopiesAndDictionariesComeLater() {
        assertThat(TitleMatch.isTranslation("Guillain-Barre_syndrome/de", "Guillain-Barre syndrome/de")).isTrue()
        assertThat(TitleMatch.isTranslation("AC/DC", "AC/DC")).isFalse()
        assertThat(TitleMatch.prior("wikipedia_en_top", "Maputo", "Maputo")).isEqualTo(0)
        assertThat(TitleMatch.prior("wiktionary_es_all", "Maputo", "Maputo")).isEqualTo(1)
        assertThat(TitleMatch.prior("wikem_en_all", "X/es", "X/es")).isEqualTo(2)
    }
}
