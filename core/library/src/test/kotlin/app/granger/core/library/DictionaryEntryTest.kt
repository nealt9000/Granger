package app.granger.core.library

import app.granger.core.zim.ZimHit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Wiktionary entries in the shape the Spanish Wiktionary ZIM has them (wiktionary_es_all, 2026). */
class DictionaryEntryTest {
    private val sobremesa = """
        <h2>Español</h2><h3>Etimología</h3><p>Del prefijo sobre- y mesa.</p>
        <h3>Sustantivo femenino</h3><p>Tapete que se pone sobre la mesa. Tiempo que se está a la mesa depués de haber comido.</p>
        <h4>Locuciones</h4><p>araña de sobremesa</p>
        <h4>Traducciones</h4><p>Alemán: Tischdecke (de).</p>
        <h2>Gallego</h2><h3>Etimología</h3><p>Del prefijo sobre- y mesa.</p>
        <h3>Sustantivo femenino</h3><p>Postre.</p>
    """.trimIndent()
    private val madrugue = """
        <h2>Español</h2><h3>Forma verbal</h3><p>Primera persona del singular (yo) del pretérito perfecto simple de indicativo de madrugar.</p>
        <h3>Referencias y notas</h3><p>Categorías</p>
    """.trimIndent()
    private val madrugar = """
        <h2>Español</h2><h3>Etimología</h3><p>Del castellano antiguo madurgar.</p>
        <h3>Verbo intransitivo</h3><p>Levantarse al alba o madrugada.</p>
    """.trimIndent()
    private val apapachar = """
        <h2>Español</h2><h3>Etimología 1</h3><p>Del náhuatl patzoa.</p>
        <h3>Verbo transitivo</h3><p>Dar abrazos, caricias o palmaditas de cariño.</p>
    """.trimIndent()

    private fun archive(results: List<ZimHit> = emptyList()) = FakeZimArchive(
        "wk", paths = setOf("sobremesa", "madrugué", "madrugar", "apapachar"),
        html = mapOf("sobremesa" to sobremesa, "madrugué" to madrugue, "madrugar" to madrugar, "apapachar" to apapachar),
        results = results,
    )

    @Test fun anEntryIsItsFirstLanguageSectionWithoutTheLists() {
        val found = DictionaryEntry.find(archive(), "sobremesa")!!
        assertThat(found.title).isEqualTo("sobremesa")
        assertThat(found.text).contains("Etimología: Del prefijo sobre- y mesa.")
        assertThat(found.text).contains("Tiempo que se está a la mesa")
        assertThat(found.text).doesNotContain("Tischdecke")
        assertThat(found.text).doesNotContain("araña")
        assertThat(found.text).doesNotContain("Postre")
    }

    @Test fun anInflectedFormLeadsToItsLemmaWithTheFormFirst() {
        val found = DictionaryEntry.find(archive(), "madrugué")!!
        assertThat(found.path).isEqualTo("madrugar")
        assertThat(found.title).isEqualTo("madrugar")
        assertThat(found.text.lines().first()).startsWith("madrugué: Primera persona del singular")
        assertThat(found.text).contains("Levantarse al alba o madrugada.")
    }

    @Test fun withNoEntryForTheWordTheSearchsSameStemTitleIsUsed() {
        val results = listOf(ZimHit("apapacho_(otro)", "apapacho de lujo"), ZimHit("apapachar", "apapachar"))
        val found = DictionaryEntry.find(archive(results), "apapachó")!!
        assertThat(found.title).isEqualTo("apapachar")
        assertThat(found.text).contains("caricias o palmaditas de cariño")
    }

    @Test fun aDifferentWordIsNotTaken() {
        assertThat(DictionaryEntry.find(archive(listOf(ZimHit("madrugar", "madrugar"))), "mar")).isNull()
        assertThat(DictionaryEntry.sameStem("guagüero", "guagua")).isTrue() // same stem; used only with no entry for the word
        assertThat(DictionaryEntry.sameStem("short-term memory", "term")).isFalse()
        assertThat(DictionaryEntry.sameStem("madrugar", "madrugué")).isTrue()
    }
}
