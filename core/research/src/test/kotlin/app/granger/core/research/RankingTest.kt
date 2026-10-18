package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RankingTest {
    private val config = RankingConfig()

    private fun passage(source: String, text: String, n: Int = 0, score: Double = 0.0) =
        Passage(SourceKind.BOOK, source, "Title $source", location = "$source-$n", text = text, searchScore = score)

    @Test
    fun preselectCapsPerSource() {
        val a = (1..6).map { passage("A", "steam engine", it) }
        val b = (1..2).map { passage("B", "steam", it) }
        val out = Ranking.preselect(a + b, listOf("steam", "engine"), config)
        assertThat(out.count { it.sourceId == "A" }).isEqualTo(3)
        assertThat(out.count { it.sourceId == "B" }).isEqualTo(2)
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
        val full = passage("A", "steam engine", 1)
        val half = passage("B", "steam", 2)
        val none = passage("C", "river", 3)
        val out = Ranking.byLexical(listOf(none, half, full), listOf("steam", "engine"), config)
        assertThat(out).containsExactly(full, half).inOrder()
    }

    @Test
    fun lexicalRankWeighsWordsOverTheWholeCorpus() {
        val target = passage("T", "steam engine")
        val others = (1..9).map { passage("S$it", "steam boiler", it) }
        val keywords = listOf("steam engine", "boiler")
        val strict = config.copy(lexicalThreshold = 0.6)
        // Alone, "boiler" looks as rare as "engine"; across the corpus "engine" is the rare word, and target has it.
        assertThat(Ranking.byLexical(listOf(target), keywords, strict)).isEmpty()
        assertThat(Ranking.byLexical(listOf(target), keywords, strict, corpus = listOf(target) + others)).containsExactly(target)
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
}
