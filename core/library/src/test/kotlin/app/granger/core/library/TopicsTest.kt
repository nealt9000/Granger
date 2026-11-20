package app.granger.core.library

import app.granger.core.vectors.open
import app.granger.core.vectors.write
import app.granger.core.research.SourceKind
import app.granger.core.text.Chunkers
import app.granger.core.vectors.ChunkKey
import app.granger.core.vectors.VectorFile
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicTimeTest {
    private fun life(qid: Long, title: String, y0: Int, y1: Int?, rank: Int) =
        TimeMark(qid, "top", title, title, TimeKind.LIFE, y0.toLong(), 0, y1?.toLong(), null, 9, setOf(Lane.PEOPLE), rank)

    private fun span(qid: Long, title: String, y0: Int, y1: Int?, rank: Int, lanes: Set<Lane> = setOf(Lane.EVENTS_POLITICS)) =
        TimeMark(qid, "top", title, title, TimeKind.SPAN, y0.toLong(), 0, y1?.toLong(), null, 9, lanes, rank)

    private fun point(qid: Long, title: String, y0: Int, rank: Int, lanes: Set<Lane>) =
        TimeMark(qid, "top", title, title, TimeKind.POINT, y0.toLong(), 0, null, null, 9, lanes, rank)

    // Napoleon's marks as the Pixel's Top index gives them (2026-11-16), plus edge cases.
    private val napoleon = life(517, "Napoleon", 1769, 1821, 288)
    private val around = listOf(
        napoleon,
        life(1, "Goethe", 1749, 1832, 280),
        life(2, "George_Washington", 1732, 1799, 275),
        life(3, "Mozart", 1756, 1791, 274),
        life(4, "Beethoven", 1770, 1827, 274),
        life(5, "Abraham_Lincoln", 1809, 1865, 400), // a boy when Napoleon died: not a contemporary
        life(6, "Isaac_Newton", 1643, 1727, 500), // dead before he was born
        life(7, "Voltaire", 1694, 1778, 260), // born 75 years before: an elder, not a contemporary
        life(8, "Jane_Austen", 1775, 1817, 204),
        life(1, "Goethe", 1749, 1832, 280), // the same person from a second pack
        span(10, "French_Revolution", 1789, 1799, 198),
        span(11, "Napoleonic_Wars", 1803, 1815, 102, setOf(Lane.WARS)),
        span(12, "Seven_Years'_War", 1756, 1763, 150, setOf(Lane.WARS)), // before he was 15
        point(13, "Mexico", 1810, 402, setOf(Lane.EVENTS_POLITICS)), // a country's founding: not an event here
        point(14, "Battle_of_Waterloo", 1815, 120, setOf(Lane.WARS)),
    )

    @Test fun contemporariesOverlappedHisAdultLifeBestKnownFirst() {
        assertThat(TopicTime.contemporaries(napoleon, around, now = 2026).map { it.path })
            .containsExactly("Goethe", "George_Washington", "Mozart", "Beethoven", "Jane_Austen").inOrder()
        assertThat(TopicTime.contemporaries(napoleon, around, now = 2026, n = 2).map { it.path })
            .containsExactly("Goethe", "George_Washington").inOrder()
    }

    @Test fun eventsAreSpansAndBattlesInHisAdultLife() {
        assertThat(TopicTime.events(napoleon, around, now = 2026).map { it.path })
            .containsExactly("French_Revolution", "Battle_of_Waterloo", "Napoleonic_Wars").inOrder()
    }

    @Test fun aSpanHasThePeopleAdultInItAndTheEventsStartingInIt() {
        val revolution = span(10, "French_Revolution", 1789, 1799, 198)
        // Napoleon was 20 in 1789 and Jane Austen adult by its end; Voltaire died in 1778.
        assertThat(TopicTime.contemporaries(revolution, around, now = 2026).map { it.path })
            .containsExactly("Napoleon", "Goethe", "George_Washington", "Mozart", "Beethoven", "Jane_Austen").inOrder()
        assertThat(TopicTime.events(revolution, around, now = 2026)).isEmpty()
    }

    @Test fun aLivingPersonRunsToNow() {
        val living = life(99, "Living", 1950, null, 10)
        val others = listOf(life(98, "Elder", 1930, null, 50), life(97, "Child", 2010, null, 60))
        assertThat(TopicTime.contemporaries(living, others, now = 2026).map { it.path }).containsExactly("Elder")
        assertThat(TopicTime.window(living, now = 2026)).isEqualTo(1905L..2026L)
    }

    @Test fun theWindowReachesBackForElders() {
        assertThat(TopicTime.window(napoleon, now = 2026)).isEqualTo(1724L..1821L)
        assertThat(TopicTime.window(point(1, "x", 1066, 1, emptySet()), now = 2026)).isEqualTo(1046L..1066L)
    }
}

class RelatedFinderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pool = Executors.newFixedThreadPool(2)

    private fun file(name: String, rows: List<Pair<String, FloatArray>>): VectorFile {
        val f = File(tmp.root, "$name.gvec")
        VectorFile.write(
            f, rows.map { it.second }, 4, rows.map { "0".repeat(16) }, rows.mapIndexed { i, r -> ChunkKey(r.first, i) },
            Chunkers.VERSION, "0".repeat(64), "0".repeat(64),
        )
        return VectorFile.open(f)!!
    }

    private fun v(vararg x: Float) = x

    private val top by lazy { file(
        "top",
        listOf(
            "Napoleon" to v(1f, 0f, 0f, 0f),
            "Napoleon" to v(0.9f, 0.1f, 0f, 0f),
            "Napoleonic_Wars" to v(0.95f, 0.2f, 0f, 0f),
            "Napoleonic_Wars" to v(0.9f, 0.3f, 0f, 0f),
            "Napoleon_Bonaparte" to v(0.99f, 0.05f, 0f, 0f), // a redirect title: the same subject
            "Jazz" to v(0f, 0f, 1f, 0f),
        ),
    ) }
    private val medicine by lazy { file("medicine", listOf("Napoleon" to v(1f, 0.02f, 0f, 0f), "Insulin" to v(0f, 1f, 0f, 0f))) }
    private val spanish by lazy { file("es", listOf("Napoleón_Bonaparte" to v(1f, 0.01f, 0f, 0f))) }
    private val warAndPeace by lazy { file(
        "wp",
        listOf("OEBPS/colophon.xhtml" to v(1f, 0f, 0f, 0f), "OEBPS/c170.xhtml" to v(0.97f, 0.1f, 0f, 0f), "OEBPS/c001.xhtml" to v(0.9f, 0.2f, 0f, 0f)),
    ) }
    private val cookbook by lazy { file("cook", listOf("OEBPS/c002.xhtml" to v(0f, 0f, 0f, 1f))) }

    private val sources by lazy { listOf(
        VectorSource(top, SourceKind.ARTICLE, "wikipedia_en_top_maxi"),
        VectorSource(medicine, SourceKind.ARTICLE, "wikipedia_en_medicine_maxi"),
        VectorSource(spanish, SourceKind.ARTICLE, "wikipedia_es_top_maxi"),
        VectorSource(warAndPeace, SourceKind.BOOK, "gc-war-and-peace"),
        VectorSource(cookbook, SourceKind.BOOK, "gc-cookbook"),
    ) }

    private fun finder(probes: Int = 1024) = RelatedFinder(
        { sources }, pool, { s -> if (s.target.contains("_es_")) "es" else "en" }, Dispatchers.Unconfined, probes,
    )

    @Test fun groupsByArticleAndBookAndDropsThePageItself() = runTest {
        val r = finder().related("wikipedia_en_top_maxi", "Napoleon", sameItem = { _, path -> path == "Napoleon_Bonaparte" })
        // Napoleon in Medicine (same path), the redirect (same item) and the Spanish article are not Related.
        assertThat(r.articles.map { it.key }).containsExactly("Napoleonic_Wars", "Insulin", "Jazz").inOrder()
        assertThat(r.articles.first().target).isEqualTo("wikipedia_en_top_maxi")
        // One hit per book, front matter skipped, and the cookbook (orthogonal) under the floor.
        assertThat(r.books.map { it.target to it.key }).containsExactly("gc-war-and-peace" to "OEBPS/c170.xhtml")
    }

    @Test fun anArticleWithoutVectorsOrAPackWithoutAFileHasNoRelated() = runTest {
        assertThat(finder().related("wikipedia_en_top_maxi", "Not_There")).isEqualTo(Related.NONE)
        assertThat(finder().related("wikipedia_en_history_maxi", "Napoleon")).isEqualTo(Related.NONE)
    }

    @Test fun resultsStayInThePagesLanguage() = runTest {
        val r = finder().related("wikipedia_es_top_maxi", "Napoleón_Bonaparte")
        assertThat(r.articles).isEmpty()
        assertThat(r.books).isEmpty()
    }

    @Test fun articlesAreCappedAndBooksNeedTheFloor() = runTest {
        val few = RelatedFinder({ sources }, pool, { "en" }, Dispatchers.Unconfined, articles = 1, bookFloor = 0.999f)
        val r = few.related("wikipedia_en_top_maxi", "Napoleon")
        assertThat(r.articles).hasSize(1)
        assertThat(r.books).isEmpty()
    }
}
