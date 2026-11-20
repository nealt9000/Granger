package app.granger.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The platform text calls give the same answers on the JVM and iOS (Plan I1). */
class PlatformTest {
    @Test fun hashes() {
        assertEquals("da39a3ee5e6b4b0d", Chunkers.hash(""))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()).hex())
    }

    @Test fun normalizes() {
        assertEquals("é", decompose("é", compat = false))
        assertEquals("fi", decompose("ﬁ", compat = true))
        assertEquals("ﬁ", decompose("ﬁ", compat = false))
        assertEquals("é", compose("é"))
    }

    @Test fun languageCodes() {
        assertEquals("es", iso639Alpha2("es"))
        assertEquals("es", iso639Alpha2("spa"))
        assertEquals("en", iso639Alpha2("eng"))
        assertEquals("de", iso639Alpha2("deu"))
        assertNull(iso639Alpha2("zz"))
        assertNull(iso639Alpha2("qqq"))
        assertEquals("es", LanguageGuess.declared("es-MX"))
        assertEquals("en", LanguageGuess.declared("English"))
    }
}
