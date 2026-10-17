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
}
