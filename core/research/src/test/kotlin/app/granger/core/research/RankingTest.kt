package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RankingTest {
    private val config = RankingConfig()

    private fun passage(source: String, text: String, n: Int = 0, score: Double = 0.0) =
        Passage(SourceKind.BOOK, source, "Title $source", location = "$source-$n", text = text, searchScore = score)

    @Test
    fun preselectCapsPerSource() {
        val a = (1..6).map { passage("A", "steam engine", it).copy(location = "A-1") }
        val b = (1..2).map { passage("B", "steam", it) }
        val out = Ranking.preselect(a + b, listOf("steam", "engine"), config)
        assertThat(out.count { it.sourceId == "A" }).isEqualTo(3)
        assertThat(out.count { it.sourceId == "B" }).isEqualTo(2)
    }

    @Test
    fun perDocumentCapCountsArticlesNotPacks() {
        fun article(title: String, n: Int) =
            Passage(SourceKind.ARTICLE, "pack", title, location = "$title.html", heading = "H$n", text = "steam engine $n")
        val ps = (1..5).map { article("One", it) } + (1..5).map { article("Two", it) }
        val out = Ranking.preselect(ps, listOf("steam", "engine"), config)
        assertThat(out.count { it.sourceTitle == "One" }).isEqualTo(3)
        assertThat(out.count { it.sourceTitle == "Two" }).isEqualTo(3)
    }

    @Test
    fun realCorpusLeadBeatsLaterSections() {
        fun article(title: String, heading: String, text: String) =
            Passage(SourceKind.ARTICLE, "wiki", title, location = "$title.html", heading = heading, text = text)
        val lead = article(
            "Mary Shelley", "Mary Shelley",
            "Mary Wollstonecraft Shelley (nee Godwin; 30 August 1797 - 1 February 1851) was an English novelist, " +
                "who wrote the Gothic novel Frankenstein (1818).",
        )
        val career = article(
            "Mary Shelley", "Return to England and writing career",
            "Mary Shelley returned to England. The publication of Frankenstein made her known.",
        )
        val editorial = article(
            "Mary Shelley", "Editorial work",
            "Mary Shelley edited the poems of Percy; publication of Frankenstein in a new edition followed.",
        )
        val film = article("Frankenstein (1931 film)", "Frankenstein (1931 film)", "Frankenstein is a 1931 American horror film.")
        val keywords = listOf("mary shelley", "frankenstein", "publication date")
        val out = Ranking.byLexical(Ranking.preselect(listOf(career, editorial, film, lead), keywords, config), keywords, config)
        assertThat(out.first()).isEqualTo(lead)
    }

    @Test
    fun preselectKeepsTwelve() {
        val many = (1..30).map { passage("S$it", "steam", it) }
        assertThat(Ranking.preselect(many, listOf("steam"), config)).hasSize(12)
    }

    @Test
    fun preselectBreaksTiesBySearchScore() {
        val low = passage("A", "steam", 1, score = 1.0)
        val high = passage("B", "steam", 1, score = 5.0)
        assertThat(Ranking.preselect(listOf(low, high), listOf("steam"), config).first()).isEqualTo(high)
    }

    @Test
    fun cosineOfParallelIsOne() {
        assertThat(Ranking.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f))).isWithin(1e-6f).of(1f)
    }

    @Test
    fun cosineOfZeroVectorIsZero() {
        assertThat(Ranking.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))).isEqualTo(0f)
    }

    @Test
    fun embeddingRankDropsBelowThreshold() {
        val ps = listOf(passage("A", "x", 1), passage("B", "x", 2), passage("C", "x", 3))
        // cosine of (1,0) with (c, s) is c; pick vectors giving 0.5, 0.9, 0.3
        fun v(sim: Float) = floatArrayOf(sim, kotlin.math.sqrt(1f - sim * sim))
        val out = Ranking.byEmbedding(ps, floatArrayOf(1f, 0f), listOf(v(0.5f), v(0.9f), v(0.3f)), config)
        assertThat(out).containsExactly(ps[1], ps[0]).inOrder()
    }

    @Test
    fun lexicalRankDropsBelowThreshold() {
        // The threshold is 0.6: two of three keywords pass, one of three doesn't.
        val full = passage("A", "steam engine boiler", 1)
        val two = passage("B", "steam engine", 2)
        val one = passage("C", "steam", 3)
        val out = Ranking.byLexical(listOf(one, two, full), listOf("steam", "engine", "boiler"), config)
        assertThat(out).containsExactly(full, two).inOrder()
    }

    @Test
    fun nothingPassesGivesEmpty() {
        val ps = listOf(passage("A", "river", 1))
        assertThat(Ranking.byLexical(ps, listOf("steam"), config)).isEmpty()
        assertThat(Ranking.byEmbedding(ps, floatArrayOf(1f, 0f), listOf(floatArrayOf(0f, 1f)), config)).isEmpty()
    }

    @Test
    fun resultsAreCappedAtLarge() {
        val ps = (1..20).map { passage("S$it", "steam", it) }
        assertThat(Ranking.byLexical(ps, listOf("steam"), config)).hasSize(8)
    }

    @Test fun cosineRejectsVectorsOfDifferentSizes() {
        val e = runCatching { Ranking.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }
}
