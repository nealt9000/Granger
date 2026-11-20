package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Spanish questions typed without accents match accented passages (Plan L4, Accents); ñ stays; English is untouched. */
class LexicalScoreEsTest {
    private val es = listOf(ResearchLanguage.ES)
    private val enEs = listOf(ResearchLanguage.EN, ResearchLanguage.ES)
    private val en = Keywords.ENGLISH

    private fun passage(text: String, title: String = "Historia", heading: String = "", kind: SourceKind = SourceKind.BOOK) =
        Passage(kind, "id", title, heading = heading, text = text)

    private val bogota = passage("Gonzalo Jiménez de Quesada fundó Santafé de Bogotá en 1538.", title = "Bogotá", heading = "Fundación")

    @Test fun foldVowelsFoldsAcuteAndDiaeresisOnly() {
        assertThat(ResearchLanguage.foldVowels("Fundó Bogotá, pingüino, ÁÉÍÓÚÜ")).isEqualTo("Fundo Bogota, pinguino, AEIOUU")
        assertThat(ResearchLanguage.foldVowels("año, señal, Ñandú")).isEqualTo("año, señal, Ñandu")
        // Grave, circumflex and the like aren't Spanish marks: left alone.
        assertThat(ResearchLanguage.foldVowels("café à la crème, naïve")).isEqualTo("cafe à la crème, naïve")
        // Decomposed marks after a vowel go; a decomposed tilde stays.
        assertThat(ResearchLanguage.foldVowels("fundé pingüino año")).isEqualTo("funde pinguino año")
    }

    @Test fun foldVowelsReturnsTheSameStringWhenThereIsNothingToFold() {
        val plain = "Steam drives the piston in a boiler: año."
        assertThat(ResearchLanguage.foldVowels(plain)).isSameInstanceAs(plain)
    }

    @Test fun unaccentedKeywordsScoreAsAccentedOnes() {
        for (languages in listOf(es, enEs)) {
            val accented = LexicalScore.of(bogota, listOf("fundó", "Bogotá"), languages)
            assertThat(accented).isWithin(1e-9).of(1.0 + 0.1)
            assertThat(LexicalScore.of(bogota, listOf("fundo", "bogota"), languages)).isEqualTo(accented)
            assertThat(LexicalScore.of(bogota, listOf("quien fundo bogota"), languages))
                .isEqualTo(LexicalScore.of(bogota, listOf("quién fundó Bogotá"), languages))
            // The other way round too: an accented keyword over unaccented text.
            assertThat(LexicalScore.of(passage("Se publico en Bogota."), listOf("publicó"), languages)).isWithin(1e-9).of(1.0)
        }
    }

    @Test fun scorerAgreesForManyPassages() {
        val score = LexicalScore.scorer(listOf("cuando se publico", "Cien años de soledad"), es)
        val book = passage("Cien años de soledad se publicó en 1967.", title = "Cien años de soledad")
        assertThat(score(book)).isEqualTo(LexicalScore.of(book, listOf("cuándo se publicó", "Cien años de soledad"), es))
        assertThat(score(book)).isWithin(1e-9).of(1.0 + 0.1)
    }

    @Test fun eneStaysDistinct() {
        val year = passage("El año 1810 trajo la independencia.")
        assertThat(LexicalScore.of(year, listOf("ano"), es)).isEqualTo(0.0)
        assertThat(LexicalScore.of(year, listOf("año"), es)).isWithin(1e-9).of(1.0)
        assertThat(LexicalScore.of(passage("Una señal de tráfico."), listOf("senal trafico"), es)).isWithin(1e-9).of(0.5)
        assertThat(LexicalScore.of(passage("El ano y el recto."), listOf("año"), es)).isEqualTo(0.0)
    }

    @Test fun titleWordsFoldToo() {
        val p = passage("nada aquí", title = "Simón Bolívar", kind = SourceKind.ARTICLE, heading = "Vida")
        assertThat(LexicalScore.of(p, listOf("simon bolivar"), es)).isWithin(1e-9).of(0.1)
        assertThat(LexicalScore.of(p, listOf("simon bolivar"), en)).isEqualTo(0.0)
    }

    @Test fun englishMatchingIsUnchanged() {
        // English alone: accents still matter, as before.
        assertThat(LexicalScore.of(bogota, listOf("fundo", "bogota"), en)).isEqualTo(0.0)
        assertThat(LexicalScore.of(passage("A café on the corner."), listOf("cafe"), en)).isEqualTo(0.0)
        assertThat(LexicalScore.of(passage("A café on the corner."), listOf("café"), en)).isWithin(1e-9).of(1.0)
    }
}
