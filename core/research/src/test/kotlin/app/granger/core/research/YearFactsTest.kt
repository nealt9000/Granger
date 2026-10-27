package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YearFactsTest {
    /**
     * `year-questions.tsv`: the answer passages of r5-t4-ent3 (Plan R5 Task 4) for the year questions, cut as the prompt
     * cuts them. Id to (question, keywords, the passages as the prompt numbered them).
     */
    private val items: Map<String, Triple<String, List<String>, List<Passage>>> by lazy {
        val out = LinkedHashMap<String, Triple<String, List<String>, MutableList<Passage>>>()
        var current: Triple<String, List<String>, MutableList<Passage>>? = null
        javaClass.getResource("/year-questions.tsv")!!.readText().lines().forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "Q" -> current = Triple(f[2], f[3].split(',').filter { it.isNotBlank() }, mutableListOf<Passage>()).also { out[f[1]] = it }
                "P" -> current!!.third += Passage(
                    SourceKind.valueOf(f[1]), "fixture", f[2], location = f[2].replace(' ', '_'), heading = f[3],
                    text = f[4].replace("\\n", "\n").replace("\\\\", "\\"),
                )
            }
        }
        out
    }

    private fun block(id: String): String? = items.getValue(id).let { (q, k, p) -> YearFacts.block(q, k, p) }

    @Test fun anAgeAtAPublication() {
        assertThat(block("multi-step-001")).contains("In 1818 (Frankenstein), Mary Shelley was alive, aged 20 or 21")
    }

    @Test fun aDeathBeforeAPublication() {
        assertThat(block("multi-step-002")).contains("In 1818 (Frankenstein), Jane Austen had died, 1 year before (18 July 1817) [3][4].")
    }

    @Test fun twoLifespans() {
        assertThat(block("multi-step-004")).contains("Charles Darwin and Abraham Lincoln were born on the same day, 12 February 1809 [3][4].")
        assertThat(block("multi-step-005")).contains("died on 22 April 1616 and William Shakespeare on 23 April 1616: 1 day apart [2][4].")
    }

    @Test fun twoEvents() {
        assertThat(block("multi-step-008")).contains("Abraham Lincoln (1861) came about 2 years after On the Origin of Species (24 November 1859) [3][4].")
        assertThat(block("multi-step-009")).contains("Mayflower (1620) came about 128 years after Christopher Columbus (1492) [1][2].")
        assertThat(block("multi-step-009")).doesNotContain("had died")
        assertThat(block("multi-step-014")).contains("Black Death (1346) came about 131 years after Magna Carta (15 June 1215) [3][4].")
    }

    @Test fun anEndIsARangesLastYear() {
        assertThat(block("multi-step-019")).contains("Fermat's Last Theorem (1994) came about 5 years after Berlin Wall (1989) [1][4].")
    }

    @Test fun anEventInAnotherArticleThatNamesIt() {
        assertThat(block("multi-step-013")).contains("Charles Darwin was alive, aged 50, and died 22 years later (19 April 1882) [2][4].")
        assertThat(block("multi-step-020")).contains("In 1869 (Dmitri Mendeleev), Charles Darwin was alive, aged 59 or 60")
    }

    @Test fun noBlockWithoutTwoDatedThings() {
        // The King James Bible's year isn't there; Noah's 950 is a count of years, and "1800 BC" isn't a year we count.
        assertThat(block("multi-step-003")).isNull()
        assertThat(block("multi-step-010")).isNull()
        assertThat(block("bible-003")).isNull()
        assertThat(block("history-004")).isNull()
        assertThat(block("medical-010")).isNull()
    }

    @Test fun lifespans() {
        assertThat(YearFacts.lifespan("Mary Wollstonecraft Shelley (née Godwin; 30 August 1797 – 1 February 1851) was"))
            .isEqualTo(YearFacts.Date(1797, 8, 30) to YearFacts.Date(1851, 2, 1))
        assertThat(YearFacts.lifespan("Abraham Lincoln (February 12, 1809 – April 15, 1865) was"))
            .isEqualTo(YearFacts.Date(1809, 2, 12) to YearFacts.Date(1865, 4, 15))
        assertThat(YearFacts.lifespan("Jane Austen (1775–1817), the author"))
            .isEqualTo(YearFacts.Date(1775) to YearFacts.Date(1817))
        assertThat(YearFacts.lifespan("Mendeleev (/ˌmɛn/; 8 February [O.S. 27 January] 1834 – 2 February [O.S. 20 January] 1907) was"))
            .isEqualTo(YearFacts.Date(1834, 2, 8) to YearFacts.Date(1907, 2, 2))
        assertThat(YearFacts.lifespan("Miguel de Cervantes (Spanish: [miˈɣel]; 29 September 1547 (assumed) – 22 April 1616) was"))
            .isEqualTo(YearFacts.Date(1547, 9, 29) to YearFacts.Date(1616, 4, 22))
        assertThat(YearFacts.lifespan("Christopher Columbus (/kəˈlʌmbəs/; between 25 August and 31 October 1451 – 20 May 1506) was"))
            .isEqualTo(YearFacts.Date(1451, 10, 31) to YearFacts.Date(1506, 5, 20))
        assertThat(YearFacts.lifespan("The Black Death (1346–1353) was a plague")).isEqualTo(YearFacts.Date(1346) to YearFacts.Date(1353))
        assertThat(YearFacts.lifespan("No bracket here, 1346 to 1353.")).isNull()
    }

    @Test fun eachWordGoesToTheNameItFollows() {
        val names = listOf("columbus", "americas", "mayflower")
        assertThat(YearFacts.eventWords("How many years after Columbus reached the Americas did the Mayflower arrive?", names))
            .containsExactly("columbus", setOf("reached"), "mayflower", setOf("arrive"))
        assertThat(YearFacts.eventWords("How long did Darwin live after publishing On the Origin of Species?", listOf("darwin", "on the origin of species")))
            .containsExactly("on the origin of species", setOf("publishing"))
    }
}
