package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FictionWeightEsTest {
    private val quijote = Passage(SourceKind.BOOK, "book:quijote", "Don Quijote de la Mancha", location = "c1", text = "la luna salía")
    private val luna = Passage(SourceKind.ARTICLE, "wiki", "Luna", location = "Luna", text = "la Luna está lejos")
    private val weight = FictionWeight({ setOf("book:quijote") }, 0.7)

    @Test fun fictionIsWeightedOnSpanishFactualQuestions() {
        val w = weight.of("¿qué tan lejos está la luna?", listOf("distancia luna"), listOf(quijote, luna))
        assertThat(w(quijote)).isEqualTo(0.7)
        assertThat(w(luna)).isEqualTo(1.0)
    }

    @Test fun spanishLiteratureQuestionsKeepFullWeight() {
        assertThat(weight.of("¿qué dice la novela sobre la luna?", listOf("luna"), listOf(quijote))(quijote)).isEqualTo(1.0)
        assertThat(weight.of("¿quién es el héroe de la historia?", listOf("héroe"), listOf(quijote))(quijote)).isEqualTo(1.0)
        assertThat(FictionWeight.literary("¿de qué trata la obra de teatro La vida es sueño?")).isTrue()
        assertThat(FictionWeight.literary("¿qué poesía escribió Neruda?")).isTrue()
        assertThat(FictionWeight.literary("que poesia escribio neruda")).isTrue()
    }

    @Test fun aSpanishKeywordNamingTheWorkDropsItsArticle() {
        // "el" is a Spanish stopword: "quijote" alone names the book found.
        assertThat(weight.of("¿cómo termina?", listOf("el quijote"), listOf(quijote))(quijote)).isEqualTo(1.0)
    }

    @Test fun spanishCuesAreOnlyForSpanish() {
        assertThat(FictionWeight.literary("novela", listOf(ResearchLanguage.EN))).isFalse()
        assertThat(FictionWeight.literary("novela", listOf(ResearchLanguage.ES))).isTrue()
    }
}
