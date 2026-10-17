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
}
