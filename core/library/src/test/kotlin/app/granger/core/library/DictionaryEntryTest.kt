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

    /** The English Wiktionary's shape (wiktionary_en_all_nopic, 2026-08): headings in div.mw-heading, quotations nested in senses. */
    @Test fun anEnglishEntryKeepsItsSensesWithoutTheirQuotations() {
        val quotations = "<ul><li>1611, King James Version: " + "and rose up betimes in the morning ".repeat(60) + "</li></ul>"
        val betimes = """
            <div class="mw-parser-output"><div class="mw-heading mw-heading2"><h2 id="English">English</h2></div>
            <div class="mw-heading mw-heading3"><h3 id="Etymology">Etymology</h3></div><p>From Middle English betymes.</p>
            <div class="mw-heading mw-heading3"><h3 id="Adverb">Adverb</h3></div><p>betimes (not comparable)</p>
            <ol><li>(dated) In good season or time; early, especially in the morning.$quotations</li><li>(archaic) In a short time, soon.</li></ol>
            <div class="mw-heading mw-heading2"><h2 id="Middle_English">Middle English</h2></div>
            <div class="mw-heading mw-heading3"><h3 id="Adverb_2">Adverb</h3></div><ol><li>at an early hour</li></ol></div>
        """.trimIndent()
        val found = DictionaryEntry.find(FakeZimArchive("wk", paths = setOf("betimes"), html = mapOf("betimes" to betimes)), "betimes")!!
        assertThat(found.text).contains("Etymology: From Middle English betymes.")
        assertThat(found.text).contains("early, especially in the morning")
        assertThat(found.text).contains("In a short time, soon.")
        assertThat(found.text).doesNotContain("King James")
        assertThat(found.text).doesNotContain("at an early hour")
    }

    @Test fun aPartTooLongIsCutAtAWordAndASplitSectionKeepsOneHeading() {
        val passages = listOf(
            app.granger.core.research.HtmlPassage("Etymology", null, "From Latin salārium. " + "word ".repeat(150)),
            app.granger.core.research.HtmlPassage("Noun", null, "A fixed amount of money paid to a worker. " + "pay ".repeat(400)),
            app.granger.core.research.HtmlPassage("Noun", null, "the rest"),
        )
        val text = DictionaryEntry.text(passages)
        assertThat(text.length).isAtMost(1_100)
        assertThat(text).contains("Noun: A fixed amount of money paid to a worker.")
        assertThat(text).endsWith(" …")
        assertThat(text.split("Noun:").size).isEqualTo(2)
    }

    @Test fun aDifferentWordIsNotTaken() {
        assertThat(DictionaryEntry.find(archive(listOf(ZimHit("madrugar", "madrugar"))), "mar")).isNull()
        assertThat(DictionaryEntry.sameStem("guagüero", "guagua")).isTrue() // same stem; used only with no entry for the word
        assertThat(DictionaryEntry.sameStem("short-term memory", "term")).isFalse()
        assertThat(DictionaryEntry.sameStem("madrugar", "madrugué")).isTrue()
    }
}
