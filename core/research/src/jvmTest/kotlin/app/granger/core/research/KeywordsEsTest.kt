package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Spanish stopwords (languages spec §6.2): on only when the question may be Spanish, checked on folded words. */
class KeywordsEsTest {
    private val es = listOf(ResearchLanguage.ES)
    private val enEs = listOf(ResearchLanguage.EN, ResearchLanguage.ES)

    @Test fun dropsSpanishStopwords() {
        assertThat(Keywords.extract("¿Cuántos años tenía Cervantes cuando salió la primera parte del Quijote?"))
            .containsExactly("años", "cervantes", "salió", "primera", "parte", "quijote").inOrder()
        assertThat(Keywords.extract("¿quién escribió La casa de los espíritus?")).containsExactly("escribió", "casa", "espíritus").inOrder()
        assertThat(Keywords.extract("dime por qué el cielo es azul")).containsExactly("cielo", "azul").inOrder()
        assertThat(Keywords.extract("cuéntame sobre la batalla de Boyacá")).containsExactly("batalla", "boyacá").inOrder()
    }

    @Test fun unaccentedTypingIsTheSame() {
        assertThat(Keywords.extract("quien escribio rayuela")).containsExactly("escribio", "rayuela").inOrder()
        assertThat(Keywords.extract("cuantos anos tenia bolivar")).containsExactly("anos", "bolivar").inOrder()
        assertThat(Keywords.isStopword("Cuántos", es)).isTrue()
        assertThat(Keywords.isStopword("cuantos", es)).isTrue()
    }

    @Test fun spanishWordsAreOnlyStopwordsForSpanish() {
        assertThat(Keywords.isStopword("cuantos")).isFalse()
        assertThat(Keywords.isStopword("tenía", listOf(ResearchLanguage.EN))).isFalse()
        assertThat(Keywords.isStopword("the", es)).isFalse()
        assertThat(Keywords.isStopword("the", enEs)).isTrue()
        assertThat(Keywords.isStopword("tenía", enEs)).isTrue()
    }

    @Test fun englishHomographsNeedSpanishFirst() {
        // "son", "sea", "Han" are English words a question may be about; only a Spanish-led question drops them.
        assertThat(Keywords.isStopword("son", es)).isTrue()
        assertThat(Keywords.isStopword("son", listOf(ResearchLanguage.ES, ResearchLanguage.EN))).isTrue()
        assertThat(Keywords.isStopword("son", enEs)).isFalse()
        assertThat(Keywords.extract("Han dynasty Sea of Japan")).containsExactly("han", "dynasty", "sea", "japan").inOrder()
        assertThat(Keywords.extract("¿cuáles son los planetas?")).containsExactly("planetas")
    }

    @Test fun noPossessiveInSpanish() {
        assertThat(Keywords.extract("¿Quién fue Bernardo O'Higgins?")).containsExactly("bernardo", "o'higgins").inOrder()
        assertThat(Keywords.extract("Darwin's finches", languages = es)).containsExactly("darwin's", "finches").inOrder()
        assertThat(Keywords.extract("Darwin's finches")).containsExactly("darwin", "finches").inOrder()
    }

    @Test fun lexicalScoreDropsSpanishStopwordsInKeywords() {
        val passage = Passage(SourceKind.ARTICLE, "wiki", "Batalla de Boyacá", location = "x", heading = "Desarrollo",
            text = "La batalla se libró junto al puente sobre el río Teatinos, cerca de Tunja, en Boyacá.")
        val keyword = listOf("la batalla del puente de boyacá")
        // English alone counts "la" and "del"; the passage lacks "del", so the keyword is 4/6 there and 4/4 in Spanish.
        assertThat(LexicalScore.of(passage, keyword, es)).isEqualTo(1.0)
        assertThat(LexicalScore.of(passage, keyword)).isLessThan(1.0)
    }
}
