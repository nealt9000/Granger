package app.granger.ebook

import app.granger.core.library.PackStatus
import app.granger.core.zim.ZimHit
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictionaryTest {
    private fun wiktionary(id: String, date: String, status: PackStatus = PackStatus.OK) =
        pack(id, status = status).copy(name = "wiktionary_en_all", date = date)

    @Test fun wiktionaryPackWins() {
        val choice = Dictionary.choose(listOf(pack("a"), wiktionary("w", "2026-01-01")), hasProcessTextHandler = true)
        assertThat(choice).isEqualTo(DictionaryChoice.Wiktionary("w"))
    }

    @Test fun newestWiktionaryIsChosen() {
        val choice = Dictionary.choose(
            listOf(wiktionary("old", "2025-03-01"), wiktionary("new", "2026-09-01"), wiktionary("mid", "2026-01-01")),
            hasProcessTextHandler = false,
        )
        assertThat(choice).isEqualTo(DictionaryChoice.Wiktionary("new"))
    }

    @Test fun processTextWhenNoPack() {
        assertThat(Dictionary.choose(listOf(pack("a")), hasProcessTextHandler = true)).isEqualTo(DictionaryChoice.ProcessText)
    }

    @Test fun noneHidesDefine() {
        assertThat(Dictionary.choose(listOf(pack("a")), hasProcessTextHandler = false)).isEqualTo(DictionaryChoice.None)
    }

    @Test fun missingWiktionaryPackIsIgnored() {
        val packs = listOf(wiktionary("gone", "2026-01-01", PackStatus.MISSING), wiktionary("bad", "2026-02-01", PackStatus.CORRUPT))
        assertThat(Dictionary.choose(packs, hasProcessTextHandler = true)).isEqualTo(DictionaryChoice.ProcessText)
    }

    @Test fun wordIsLowerCasedTrimmedAndReducedToTheFirstWord() {
        assertThat(Dictionary.normalise("  “Brahman,”  ")).isEqualTo("brahman")
        assertThat(Dictionary.normalise("Fire, and water")).isEqualTo("fire")
        assertThat(Dictionary.normalise("...")).isEqualTo("")
    }

    @Test fun exactTitleBeatsFirstHit() {
        val hits = listOf(ZimHit("a", "Fires"), ZimHit("b", "FIRE"))
        assertThat(Dictionary.pick(hits, "fire")!!.path).isEqualTo("b")
        assertThat(Dictionary.pick(listOf(ZimHit("a", "Fires")), "fire")!!.path).isEqualTo("a")
        assertThat(Dictionary.pick(emptyList(), "fire")).isNull()
    }

    // Plan L3: which Wiktionary, and where in it.
    private val en = pack("en-w").copy(name = "wiktionary_en_all", date = "2026-01-01")
    private val es = pack("es-w").copy(name = "wiktionary_es_all", date = "2026-02-01", language = "spa")
    private val fr = pack("fr-w").copy(name = "wiktionary_fr_all", date = "2026-03-01", language = "fra")

    @Test fun aLearnerReadsTheMainLanguagesWiktionary() {
        // An English library reading a Spanish book: English Wiktionary, at its Spanish section.
        assertThat(Dictionary.choose(listOf(en, es), "es", listOf("en"))).isEqualTo(Definer("en-w", "en", "Spanish", fallback = false))
    }

    @Test fun elseTheBooksOwnLanguagesWiktionary() {
        // A Spanish library reading a Spanish book: Spanish Wiktionary at Español.
        assertThat(Dictionary.choose(listOf(en, es), "spa", listOf("es"))).isEqualTo(Definer("es-w", "es", "Español", fallback = false))
        // A Spanish library reading an English book, with no Spanish Wiktionary: English's, at English.
        assertThat(Dictionary.choose(listOf(en), "en", listOf("es"))).isEqualTo(Definer("en-w", "en", "English", fallback = false))
        // A book that doesn't say is in the main language.
        assertThat(Dictionary.choose(listOf(en, es), null, listOf("en"))).isEqualTo(Definer("en-w", "en", "English", fallback = false))
    }

    @Test fun elseAnyWiktionaryWithANote() {
        // A Spanish library, a Spanish book, only English Wiktionary... is case 1. Only French Wiktionary: a fallback.
        assertThat(Dictionary.choose(listOf(fr), "es", listOf("es"))).isEqualTo(Definer("fr-w", "fr", "Espagnol", fallback = true))
        assertThat(Dictionary.choose(listOf(fr, en), "de", listOf("es"))!!.packId).isEqualTo("fr-w")
        assertThat(Dictionary.choose(listOf(pack("a")), "es", listOf("es"))).isNull()
    }

    @Test fun theAnchorIsTheBookLanguageInTheWiktionarysLanguage() {
        assertThat(Dictionary.anchor("es", "en")).isEqualTo("Spanish")
        assertThat(Dictionary.anchor("es", "es")).isEqualTo("Español")
        assertThat(Dictionary.anchor("en", "es")).isEqualTo("Inglés")
    }
}
