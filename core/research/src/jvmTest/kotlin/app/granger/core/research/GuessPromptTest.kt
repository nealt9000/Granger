package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuessPromptTest {
    @Test
    fun saysThereAreNoSourcesAndAsksForBrevity() {
        val prompt = GuessPrompt.build("Why is the sky blue?", priorQuestion = null)
        assertThat(prompt).contains("no sources")
        assertThat(prompt).contains("say so")
        assertThat(prompt).contains("Question: Why is the sky blue?")
        assertThat(prompt).doesNotContain("Previous question:")
        val followUp = GuessPrompt.build("And at sunset?", priorQuestion = "Why is the sky blue?")
        assertThat(followUp).contains("Previous question: Why is the sky blue?")
    }

    @Test
    fun staysUnder400EstimatedTokens() {
        val prompt = GuessPrompt.build("q".repeat(1000), priorQuestion = "p".repeat(300))
        assertThat(prompt.length).isAtMost(1_600)
    }
}
