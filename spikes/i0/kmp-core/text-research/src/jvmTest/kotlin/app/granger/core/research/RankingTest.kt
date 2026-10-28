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
    fun weightReordersAndIsThresholded() {
        val novel = passage("Novel", "steam engine boiler", 1)
        val ref = passage("Ref", "steam engine", 2)
        val keywords = listOf("steam", "engine", "boiler")
        val weight = { p: Passage -> if (p.sourceId == "Novel") 0.5 else 1.0 }
        assertThat(Ranking.preselect(listOf(novel, ref), keywords, config, weight)).containsExactly(ref, novel).inOrder()
        // 0.5 × 1.0 is under the 0.6 threshold; 2/3 isn't.
        assertThat(Ranking.byLexical(listOf(novel, ref), keywords, config, weight = weight)).containsExactly(ref)
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

    private fun media(n: Int) = Passage(SourceKind.MEDIA, "album:a$n", "Album $n", packTitle = "Your music", text = "Album: Album $n")
    private fun book(n: Int) = passage("B$n", "steam", n)

    @Test fun mediaCapKeepsOneMediaPassageForANonMediaQuestion() {
        val ranked = listOf(media(1), book(1), media(2), book(2))
        assertThat(Ranking.capMedia(ranked, asked = false, config)).containsExactly(media(1), book(1), book(2)).inOrder()
    }

    @Test fun mediaCuesRaiseTheCapToTwo() {
        val ranked = listOf(media(1), book(1), media(2), media(3), book(2))
        assertThat(Ranking.capMedia(ranked, asked = true, config)).containsExactly(media(1), book(1), media(2), book(2)).inOrder()
    }

    @Test fun cappedMediaGivesItsPlaceToTheNextPassage() {
        // Ranked best first, more than the pool holds: 3 media among the first 8.
        val ranked = listOf(book(1), media(1), book(2), media(2), book(3), media(3), book(4), book(5), book(6), book(7), book(8))
        val pool = Ranking.capMedia(ranked, asked = false, config).take(config.large)
        assertThat(pool).containsExactly(book(1), media(1), book(2), book(3), book(4), book(5), book(6), book(7)).inOrder()
    }

    @Test fun capLeavesOtherKindsUntouched() {
        val ranked = (1..10).map { book(it) } + Passage(SourceKind.CARD, "c", "Card", text = "Title: x")
        assertThat(Ranking.capMedia(ranked, asked = false, config)).isEqualTo(ranked)
    }

    @Test fun liftMediaPutsTheBestFoundMediaFirstWhenAsked() {
        // Ranking dropped both media passages; preselect's order says media(2) is the better one.
        val found = listOf(book(1), media(2), book(2), media(1))
        val ranked = listOf(book(1), book(2))
        assertThat(Ranking.liftMedia(ranked, found, asked = true)).containsExactly(media(2), book(1), book(2)).inOrder()
        // Already ranked: moved to the front, not repeated.
        assertThat(Ranking.liftMedia(listOf(book(1), media(2)), found, asked = true)).containsExactly(media(2), book(1)).inOrder()
    }

    @Test fun liftMediaChangesNothingUnlessAskedAndFound() {
        val ranked = listOf(book(1), book(2))
        assertThat(Ranking.liftMedia(ranked, listOf(media(1)) + ranked, asked = false)).isEqualTo(ranked)
        assertThat(Ranking.liftMedia(ranked, ranked, asked = true)).isEqualTo(ranked)
    }

    @Test fun mediaNeverTakesPreselectSlots() {
        val strong = (1..12).map { passage("S$it", "steam engine", it) }
        val weaker = (13..15).map { passage("S$it", "steam", it) }
        val media = listOf(
            Passage(SourceKind.MEDIA, "album:a1", "Steam", text = "Album: Steam\nTracks: steam engine"),
            Passage(SourceKind.MEDIA, "album:a2", "Engines", text = "Album: Engines\nTracks: steam engine"),
        )
        val keywords = listOf("steam", "engine")
        val without = Ranking.preselect(strong + weaker, keywords, config)
        val with = Ranking.preselect(media + strong + weaker, keywords, config)
        assertThat(with.filter { it.kind != SourceKind.MEDIA }).isEqualTo(without)
        assertThat(without).isEqualTo(strong)
        assertThat(with.filter { it.kind == SourceKind.MEDIA }).containsExactlyElementsIn(media)
    }

    @Test fun rankingCanKeepMoreThanLargeForTheCap() {
        val ps = (1..20).map { passage("S$it", "steam", it) }
        assertThat(Ranking.byLexical(ps, listOf("steam"), config, limit = 12)).hasSize(12)
    }

    @Test fun cosineRejectsVectorsOfDifferentSizes() {
        val e = runCatching { Ranking.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f)) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    // Ranking.hybrid: the cases of tools/eval/tests/test_merge.py.
    private fun article(text: String, title: String, cosine: Double? = null) =
        Passage(SourceKind.ARTICLE, "wikipedia_en_top", title, location = title, text = text, cosine = cosine)

    @Test
    fun hybridListsADenseHitWithAKeywordTwinOnce() {
        val lexical = article("Rust is an iron oxide formed by oxidation.", "Rust")
        val dense = article("Rust is an iron oxide  formed by oxidation.", "Rust", cosine = 0.82)
        assertThat(Ranking.hybrid(listOf(lexical), listOf(dense), listOf("rust"), RankingConfig()).map { it.text })
            .containsExactly("Rust is an iron oxide formed by oxidation.")
    }

    @Test
    fun hybridLeavesOutDenseHitsBelowTheFloor() {
        val hits = listOf(article("Moon distance is 384,400 km.", "Moon", 0.80), article("A poem about the night.", "Night", 0.70))
        assertThat(Ranking.hybrid(emptyList(), hits, listOf("moon"), RankingConfig()).map { it.sourceTitle })
            .containsExactly("Moon")
    }

    @Test
    fun hybridGateEmptiesWeakDenseAndPartialKeywordMatches() {
        val lexical = article("The bank of the river was steep.", "River")
        val hits = listOf(article("Banks hold deposits.", "Bank", 0.75))
        assertThat(Ranking.hybrid(listOf(lexical), hits, listOf("bank", "balance"), RankingConfig())).isEmpty()
    }

    @Test
    fun hybridGatePassesAStrongDenseHitOrACompleteKeywordMatch() {
        val paris = article("Paris is the capital of France.", "Paris")
        assertThat(Ranking.hybrid(emptyList(), listOf(paris.copy(cosine = 0.85)), listOf("capital", "france"), RankingConfig())).hasSize(1)
        assertThat(Ranking.hybrid(listOf(paris), emptyList(), listOf("capital", "france"), RankingConfig())).containsExactly(paris)
    }

    @Test
    fun hybridOrdersByTheBlendAndWeightsFictionCosines() {
        val novel = Passage(SourceKind.BOOK, "book:dracula", "Dracula", location = "c1", text = "The moon rose red.", cosine = 0.90)
        val moon = article("The Moon is Earth's only natural satellite.", "Moon", 0.80)
        val fiction = { p: Passage -> if (p.sourceId == "book:dracula") 0.7 else 1.0 }
        assertThat(Ranking.hybrid(emptyList(), listOf(novel, moon), listOf("moon"), RankingConfig()).map { it.sourceTitle })
            .containsExactly("Dracula", "Moon").inOrder()
        // 0.90 × 0.7 is under the floor.
        assertThat(Ranking.hybrid(emptyList(), listOf(novel, moon), listOf("moon"), RankingConfig(), fiction).map { it.sourceTitle })
            .containsExactly("Moon")
    }
}
