package app.granger

import app.granger.core.catalog.PackEntry
import app.granger.core.library.SearchText
import app.granger.screens.CivilDate
import app.granger.screens.bytesText
import app.granger.sources.CatalogScope
import app.granger.sources.KiwixList
import app.granger.sources.hostOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan I4 Task 2: Get more's words and dates on the iPhone. */
class CatalogWordsIosTest {
    @Test fun dates() {
        assertEquals(CivilDate(2026, 3, 5), CivilDate.parse("2026-03-05T00:00"))
        assertNull(CivilDate.parse("2026-02-30"))
        assertEquals(0L, CivilDate(1970, 1, 1).epochDay)
        assertEquals(CivilDate(2027, 3, 5), CivilDate(2026, 3, 5).plusDays(365))
        assertEquals(CivilDate.ofEpochDay(20_000), CivilDate.ofEpochDay(CivilDate.ofEpochDay(20_000).epochDay))
        assertTrue(CivilDate(2026, 3, 5).medium().contains("2026"))
    }

    @Test fun words() {
        assertTrue(bytesText(2_000_000_000).endsWith(" GB"))
        assertEquals("512 B", bytesText(512))
        assertEquals("encyclopedie eve", SearchText.fold("Encyclopédie Ève"))
        assertEquals(listOf("cafe", "ole"), SearchText.words("Café, olé!"))
        assertEquals("download.kiwix.org", hostOf("https://download.kiwix.org/zim/x.zim"))
        assertEquals("English", KiwixList.languageName("eng"))
        assertEquals("sources_scope_kiwix", CatalogScope.KIWIX.titleKey)
        assertNotNull(KiwixList.updated("2026-03-05"))
        val text = KiwixList.updated("2026-03-05") as UiText.Res
        assertTrue((text.args.single() as String).contains("2026"))
    }

    @Test fun groupsFlavours() {
        val maxi = PackEntry(
            id = "wikimed_maxi", title = "WikiMed", category = "Medical", language = "eng", sizeBytes = 2_000, sha256 = "",
            urls = emptyList(), snapshotDate = "2026-03-05", license = "CC BY-SA", hasFulltextIndex = true, zimUuid = "u", zimName = "wikimed", flavour = "maxi",
        )
        val nopic = maxi.copy(id = "wikimed_nopic", flavour = "nopic", sizeBytes = 1_000)
        val g = KiwixList.group(listOf(nopic, maxi), freeBytes = 1_500).single()
        assertEquals(listOf("wikimed_maxi", "wikimed_nopic"), g.flavours.map { it.id })
        assertEquals("wikimed_nopic", g.default.id)
    }
}
