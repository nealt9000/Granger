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

    // R2 Task 3: the block ends with the comparison the question asks, in words.
    @Test fun theBlockSaysItsConclusion() {
        assertThat(block("multi-step-004")).contains("So, for the question: Neither is older: Charles Darwin and Abraham Lincoln were born on the same day, 12 February 1809 [3][4].")
        assertThat(block("multi-step-011")).contains("So, for the question: Yes, as far as the dates go: in 1492 (Christopher Columbus), Niccolò Machiavelli was alive [2][3].")
        assertThat(block("multi-step-002")).contains("So, for the question: No: in 1818 (Frankenstein), Jane Austen had already died (18 July 1817) [3][4].")
    }

    @Test fun cueOnlyKeywords() {
        assertThat(YearFacts.cueOnly("years passed")).isTrue()
        assertThat(YearFacts.cueOnly("how old")).isTrue()
        assertThat(YearFacts.cueOnly("berlin wall")).isFalse()
        assertThat(YearFacts.cueOnly("years of solitude")).isFalse()
        assertThat(YearFacts.cueOnly("")).isFalse()
    }

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

    @Test fun anEventNamedInAnotherArticleTakesTheYearAfterTheName() {
        // Columbus's own lead is cut before 1492; Madoc's passage has "before Christopher Columbus's voyage in 1492".
        assertThat(block("multi-step-011")).contains("Niccolò Machiavelli was alive, aged 22 or 23")
    }

    @Test fun noBlockWhenTheAskedEventIsntDated() {
        // The periodic table's 1869 isn't in these passages; Mendeleev's death would answer another question.
        assertThat(block("multi-step-018")).isNull()
    }

    @Test fun noBlockWithoutTwoDatedThings() {
        // The King James Bible's year isn't there; Noah's 950 is a count of years, and "1800 BC" isn't a year we count.
        assertThat(block("multi-step-003")).isNull()
        assertThat(block("multi-step-010")).isNull()
        assertThat(block("bible-003")).isNull()
        assertThat(block("history-004")).isNull()
        assertThat(block("medical-010")).isNull()
        // "cosmology" was a keyword, not in the question.
        assertThat(block("science-004")).isNull()
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

    // R2 multi-step: what the four passages miss, further down.
    private fun article(title: String, text: String, heading: String = title) =
        Passage(SourceKind.ARTICLE, "wiki", title, location = title.replace(' ', '_'), heading = heading, text = text)
    private val mayflower = (1..4).map { n ->
        article("Mayflower", "Mayflower was an English ship that transported the Pilgrims to the New World in 1620. Part $n.", if (n == 1) "Mayflower" else "Part $n")
    }
    private val q009 = "How many years after Columbus reached the Americas did the Mayflower arrive?"

    @Test fun theOfNameGoesToTheNameAfter() {
        val names = listOf("declaration of independence", "berlin wall")
        assertThat(YearFacts.eventWords("How many years passed between the Declaration of Independence and the fall of the Berlin Wall?", names))
            .containsExactly("berlin wall", setOf("fall"))
    }

    @Test fun anEndWithoutItsWordTakesTheRangeNotThePassagesFirstYear() {
        val passages = listOf(
            article("Berlin Wall", "After 1961, the deadly force associated with the Wall prevented almost all such emigration.", "Emigration"),
            article("Berlin Wall", "The Berlin Wall was a concrete barrier separating Western and Eastern parts of Berlin from 1961 to 1989."),
            article("United States Declaration of Independence", "The Declaration of Independence was adopted on July 4, 1776."),
        )
        assertThat(YearFacts.block("How many years passed between the Declaration of Independence and the fall of the Berlin Wall?", emptyList(), passages))
            .contains("Berlin Wall (1989) came about 213 years after United States Declaration of Independence (4 July 1776)")
    }

    @Test fun aPassageFurtherDownDatesWhatTheFirstPlacesCant() {
        val voyages = article("Voyages of Columbus", "Christopher Columbus made four voyages from Spain to the Caribbean, and reached the Americas in 1492.")
        val ohio = article("Columbus, Ohio", "The National Road reached Columbus from Baltimore in 1831.")
        assertThat(YearFacts.block(q009, emptyList(), mayflower)).isNull()
        val done = YearFacts.complete(q009, emptyList(), mayflower, pool = listOf(ohio), keep = 4, byMeaning = listOf(voyages))
        assertThat(done.take(4)).contains(voyages)
        assertThat(done.take(4)).doesNotContain(ohio)
        assertThat(done).containsExactlyElementsIn(mayflower + voyages)
        assertThat(YearFacts.block(q009, emptyList(), done.take(4))).contains("came about 128 years after Columbus (1492)")
        // A keyword hit that only names it isn't searched further down.
        assertThat(YearFacts.complete(q009, emptyList(), mayflower, pool = listOf(ohio), keep = 4)).isEqualTo(mayflower)
    }

    @Test fun whatTheFirstPlacesDateIsLeftAlone() {
        val passages = items.getValue("multi-step-008").third
        val (q, k, _) = items.getValue("multi-step-008")
        assertThat(YearFacts.complete(q, k, passages, pool = passages.reversed(), keep = 4)).isEqualTo(passages)
    }

    @Test fun aPersonsEventIsOneTheyAreIn() {
        val passages = listOf(
            article("Dmitri Mendeleev", "Dmitri Mendeleev (8 February 1834 – 2 February 1907) was a Russian chemist."),
            article("Dmitri Mendeleev", "Another person to propose a periodic table was Lothar Meyer, who published a paper in 1864.", "Periodic table"),
            article("Mendeleev's predicted elements", "Dmitri Mendeleev published a periodic table of the chemical elements in 1869."),
            article("Marie Curie", "Marie Curie (7 November 1867 – 4 July 1934) was a chemist. She won the 1911 Nobel Prize in Chemistry."),
        )
        val block = YearFacts.block("How many years after Mendeleev's periodic table did Marie Curie win her chemistry Nobel?", emptyList(), passages)
        assertThat(block).contains("1869 [3]")
        assertThat(block).doesNotContain("1864")
    }

    @Test fun theVerdictAloneIsAnAnswer() {
        val (q, k, p) = items.getValue("multi-step-002")
        assertThat(YearFacts.answer(q, k, p)).isEqualTo("No: in 1818 (Frankenstein), Jane Austen had already died (18 July 1817) [3][4].")
        val (q2, k2, p2) = items.getValue("multi-step-009")
        assertThat(YearFacts.answer(q2, k2, p2)).isNull()
    }
}
