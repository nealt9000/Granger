package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FictionWeightTest {
    private val dracula = Passage(SourceKind.BOOK, "book:dracula", "Dracula", location = "c1", text = "the moon rose")
    private val moon = Passage(SourceKind.ARTICLE, "wiki", "Moon", location = "Moon", text = "the moon is far")
    private val weight = FictionWeight({ setOf("book:dracula") }, 0.7)

    @Test
    fun fictionIsWeightedOnFactualQuestions() {
        val w = weight.of("how far away is the moon", listOf("moon distance"), listOf(dracula, moon))
        assertThat(w(dracula)).isEqualTo(0.7)
        assertThat(w(moon)).isEqualTo(1.0)
    }

    @Test
    fun literatureQuestionsKeepFullWeight() {
        assertThat(weight.of("what does the novel say about the moon", listOf("moon"), listOf(dracula))(dracula)).isEqualTo(1.0)
        // A keyword naming the fiction work found.
        assertThat(weight.of("how does it end", listOf("dracula", "ending"), listOf(dracula))(dracula)).isEqualTo(1.0)
    }

    @Test
    fun noFictionListMeansNoWeight() {
        val w = FictionWeight({ emptySet() }).of("how far away is the moon", listOf("moon"), listOf(dracula))
        assertThat(w(dracula)).isEqualTo(1.0)
    }
}
