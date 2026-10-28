package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerPromptTest {
    private val darwin = Passage(
        kind = SourceKind.BOOK, sourceId = "b1", sourceTitle = "On the Origin of Species", heading = "Chapter IV",
        text = "Natural selection acts daily.",
        meta = SourceMeta(author = "Charles Darwin", date = "1859", collection = "Granger Classics"),
    )

    private fun build(
        passages: List<Passage> = listOf(darwin),
        medical: Boolean = false,
        prior: PriorTurn? = null,
        hasQuote: Boolean = false,
    ) = AnswerPrompt.build("What is selection?", passages, medical, prior, hasQuote)

    @Test
    fun numbersPassagesWithTheirMetadata() {
        val p = build(listOf(darwin, darwin.copy(sourceTitle = "Other", meta = SourceMeta(), heading = "")))
        assertThat(p).contains("[1] On the Origin of Species — Charles Darwin (1859) · Granger Classics · Chapter IV\nNatural selection acts daily.")
        assertThat(p).contains("[2] Other\nNatural selection acts daily.")
        assertThat(p).contains("Question: What is selection?\nAnswer:")
    }

    @Test
    fun theHeaderIsLengthCapped() {
        val long = darwin.copy(sourceTitle = "T".repeat(5_000), meta = SourceMeta(author = "A".repeat(5_000)))
        val header = build(listOf(long)).lines().first { it.startsWith("[1]") }
        assertThat(header.length).isAtMost(241)
    }

    private fun rules(medical: Boolean = false): List<String> =
        build(medical = medical).lines().dropWhile { it != "Rules:" }.drop(1).takeWhile { it.startsWith("- ") }

    @Test
    fun promptAsksForTheAnswerFirst() {
        val rules = rules()
        val first = rules.indexOfFirst { it.contains("Answer the question directly in your first sentence") }
        val decline = rules.indexOfFirst { it.contains(AnswerPrompt.DECLINE_TOKEN) }
        assertThat(first).isAtLeast(0)
        assertThat(first).isLessThan(decline)
    }

    @Test
    fun promptAllowsCombiningPassages() {
        assertThat(rules().joinToString("\n")).contains("combine facts from different passages and work out simple things from them (dates, ages")
    }

    @Test
    fun declineRuleIsLastAndPlain() {
        val last = rules().last()
        assertThat(last).startsWith("- If the passages don't answer the question, reply exactly")
        assertThat(last).contains("NOT IN LIBRARY")
        assertThat(rules(medical = true).last()).contains("medical question")
    }

    @Test
    fun passagesAboutSomethingElseDontAnswer() {
        val rules = rules()
        val other = rules.indexOfFirst { it.contains("different person, year, place or event") }
        assertThat(other).isAtLeast(0)
        assertThat(other).isLessThan(rules.lastIndex)
    }

    @Test
    fun medicalRuleOnlyWhenMedical() {
        assertThat(build(medical = true)).contains("This is a medical question.")
        assertThat(build(medical = false)).doesNotContain("medical question")
    }

    @Test
    fun priorTurnIsStrippedAndCut() {
        val answer = "Steam [1] expands. ".repeat(110)
        val p = build(prior = PriorTurn("Earlier?", answer))
        val line = p.lines().first { it.startsWith("A: ") }
        assertThat(line).doesNotContain("[1]")
        assertThat(line.length).isAtMost(601 + 3)
        assertThat(line).endsWith("…")
        assertThat(p).contains("Q: Earlier?")
        assertThat(build()).doesNotContain("Earlier in this conversation")
    }

    @Test
    fun quoteLineWhenQuoted() {
        assertThat(build(hasQuote = true)).contains("The user selected passage [1] and is asking about it.")
        assertThat(build(hasQuote = false)).doesNotContain("selected passage")
    }

    @Test
    fun longTextIsCutAtAWordBoundary() {
        val p = build(listOf(darwin.copy(text = "word ".repeat(400))))
        val body = p.lines().first { it.startsWith("word") }
        assertThat(body.length).isAtMost(1201)
        assertThat(body).endsWith("word…")
    }

    @Test
    fun promptStaysUnderBudgetAtTheLimits() {
        val big = "lorem ipsum ".repeat(200)
        val ps = listOf(darwin.copy(kind = SourceKind.SELECTION, text = "quote ".repeat(900))) +
            (2..8).map { darwin.copy(text = big) }
        val p = build(ps, medical = true, prior = PriorTurn("Q", "answer ".repeat(500)), hasQuote = true)
        assertThat(AnswerPrompt.estimateTokens(p)).isAtMost(3_400)
    }

    @Test
    fun budgetEnforcedWithLongNonEnglishPassages() {
        // Cyrillic: about one token per 2-3 characters, so chars / 4 would undercount it.
        val russian = "Природа и закон естественного отбора ".repeat(32)
        val ps = (1..8).map { darwin.copy(sourceTitle = "Книга $it", text = russian) }
        val kept = AnswerPrompt.fit("Что такое отбор?", ps, medical = false, prior = null, hasQuote = false)
        assertThat(kept.size).isLessThan(8)
        assertThat(kept).isNotEmpty()
        val p = AnswerPrompt.build("Что такое отбор?", ps, medical = false, prior = null, hasQuote = false)
        assertThat(AnswerPrompt.estimateTokens(p)).isAtMost(3_400)
        assertThat(p).doesNotContain("[${kept.size + 1}]")
    }

    @Test
    fun trimsLastPassageFirst() {
        val russian = "Природа и закон естественного отбора ".repeat(32)
        val ps = (1..8).map { darwin.copy(sourceTitle = "Книга $it", text = russian) }
        val kept = AnswerPrompt.fit("Что такое отбор?", ps, medical = false, prior = null, hasQuote = false)
        assertThat(kept).isEqualTo(ps.take(kept.size))
        val p = AnswerPrompt.build("Что такое отбор?", ps, medical = false, prior = null, hasQuote = false)
        assertThat(p).contains("[${kept.size}] Книга ${kept.size}")
        assertThat(p).doesNotContain("Книга ${kept.size + 1}")
    }

    @Test
    fun englishPassagesThatFitAreAllKept() {
        val ps = (1..8).map { darwin.copy(text = "word ".repeat(100)) }
        assertThat(AnswerPrompt.fit("Q?", ps, false, null, false)).isEqualTo(ps)
    }

    @Test
    fun theFirstPassageIsNeverDropped() {
        val huge = darwin.copy(kind = SourceKind.SELECTION, text = "я".repeat(5_000))
        assertThat(AnswerPrompt.fit("Q?", listOf(huge), false, PriorTurn("Q", "я".repeat(5_000)), true)).hasSize(1)
    }
}
