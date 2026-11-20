package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordQuestionTest {
    private fun word(q: String) = WordQuestion.word(q, ResearchLanguage.of(q))

    @Test fun spanishWordQuestionsFromTuneEs() {
        assertThat(word("qué significa que alguien te apapachó")).isEqualTo("apapachó")
        assertThat(word("¿qué es la ñapa?")).isEqualTo("ñapa")
        assertThat(word("que significa madrugué")).isEqualTo("madrugué")
        assertThat(word("¿qué es la sobremesa?")).isEqualTo("sobremesa")
        assertThat(word("por qué en chile a los bebés les dicen guagua")).isEqualTo("guagua")
    }

    @Test fun otherSpanishPhrasings() {
        assertThat(word("¿de dónde viene la palabra ojalá?")).isEqualTo("ojalá")
        assertThat(word("cuál es el origen de la palabra chévere")).isEqualTo("chévere")
        assertThat(word("¿Qué quiere decir chamba?")).isEqualTo("chamba")
    }

    @Test fun englishWordQuestions() {
        assertThat(word("what does betimes mean")).isEqualTo("betimes")
        assertThat(word("where does the word quarantine come from")).isEqualTo("quarantine")
        assertThat(word("what is the origin of the mexican slang word chamba")).isEqualTo("chamba")
    }

    @Test fun otherQuestionsAreNotWordQuestions() {
        assertThat(word("¿cuándo fue la batalla de Ayacucho?")).isNull()
        assertThat(word("en que año se fundo tenochtitlan")).isNull()
        assertThat(word("who wrote One Hundred Years of Solitude")).isNull()
        // "what is" alone isn't an English word question: "what is a black hole" wants the encyclopedia.
        assertThat(word("what is a sextant")).isNull()
        assertThat(word("¿qué es la fotosíntesis de las plantas?")).isNull()
    }
}
