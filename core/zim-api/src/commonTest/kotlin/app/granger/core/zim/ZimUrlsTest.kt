package app.granger.core.zim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZimUrlsTest {
    @Test fun forEntryEncodesTheFragment() {
        val url = ZimUrls.forEntry("p", "Page", "Early life#1 é")
        assertEquals("https://zim.granger.invalid/p/Page#Early%20life%231%20%C3%A9", url)
        assertEquals(ZimUrl("p", "Page", "Early life#1 é"), ZimUrls.parse(url))
    }

    private fun roundTrip(path: String) =
        assertEquals(ZimUrl("pack-1", path), ZimUrls.parse(ZimUrls.forEntry("pack-1", path)))

    @Test fun plainPath() {
        assertEquals("https://zim.granger.invalid/pack-1/Photosynthesis", ZimUrls.forEntry("pack-1", "Photosynthesis"))
        roundTrip("Photosynthesis")
    }

    @Test fun specialCharactersRoundTrip() {
        roundTrip("C++")
        roundTrip("What_is_this?")
        roundTrip("Café_au_lait")
        roundTrip("100%_Pure")
        roundTrip("A/Old_namespace_article")
        roundTrip("Emoji_😀")
        roundTrip("Space and \"quotes\" #hash")
    }

    @Test fun encodesReservedCharacters() {
        val url = ZimUrls.forEntry("p", "What_is_this?")
        assertEquals("https://zim.granger.invalid/p/What_is_this%3F", url)
        assertEquals("https://zim.granger.invalid/p/C%2B%2B", ZimUrls.forEntry("p", "C++"))
    }

    @Test fun keepsSlashesBetweenSegments() {
        assertEquals("https://zim.granger.invalid/p/I/leaf.png", ZimUrls.forEntry("p", "I/leaf.png"))
    }

    @Test fun rawPlusIsAPlusNotASpace() {
        assertEquals(ZimUrl("p", "C++"), ZimUrls.parse("https://zim.granger.invalid/p/C++"))
    }

    @Test fun keepsFragmentAndDropsQuery() {
        assertEquals(ZimUrl("p", "Photosynthesis", "s3"), ZimUrls.parse("https://zim.granger.invalid/p/Photosynthesis?x=1#s3"))
        assertEquals("https://zim.granger.invalid/p/Photosynthesis#s3", ZimUrls.forEntry("p", "Photosynthesis", "s3"))
    }

    @Test fun malformedEscapesAreKeptLiterally() {
        assertEquals(ZimUrl("p", "100%_Pure%2"), ZimUrls.parse("https://zim.granger.invalid/p/100%_Pure%2"))
    }

    @Test fun emptyPathParses() {
        assertEquals(ZimUrl("p", ""), ZimUrls.parse("https://zim.granger.invalid/p/"))
    }

    @Test fun foreignUrlsAreNotZimUrls() {
        assertNull(ZimUrls.parse("https://example.com/p/Photosynthesis"))
        assertNull(ZimUrls.parse("https://zim.granger.invalid.evil.com/p/x"))
        assertNull(ZimUrls.parse("http://zim.granger.invalid/p/x"))
        assertNull(ZimUrls.parse("https://zim.granger.invalid/"))
        assertNull(ZimUrls.parse("https://zim.granger.invalid/onlypack"))
        assertNull(ZimUrls.parse("javascript:alert(1)"))
    }
}
