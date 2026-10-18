package app.granger.core.zim

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZimUrlsTest {
    @Test fun forEntryEncodesTheFragment() {
        val url = ZimUrls.forEntry("p", "Page", "Early life#1 é")
        assertThat(url).isEqualTo("https://zim.granger.invalid/p/Page#Early%20life%231%20%C3%A9")
        assertThat(ZimUrls.parse(url)).isEqualTo(ZimUrl("p", "Page", "Early life#1 é"))
    }

    private fun roundTrip(path: String) =
        assertThat(ZimUrls.parse(ZimUrls.forEntry("pack-1", path))).isEqualTo(ZimUrl("pack-1", path))

    @Test fun plainPath() {
        assertThat(ZimUrls.forEntry("pack-1", "Photosynthesis"))
            .isEqualTo("https://zim.granger.invalid/pack-1/Photosynthesis")
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
        assertThat(url).isEqualTo("https://zim.granger.invalid/p/What_is_this%3F")
        assertThat(ZimUrls.forEntry("p", "C++")).isEqualTo("https://zim.granger.invalid/p/C%2B%2B")
    }

    @Test fun keepsSlashesBetweenSegments() {
        assertThat(ZimUrls.forEntry("p", "I/leaf.png")).isEqualTo("https://zim.granger.invalid/p/I/leaf.png")
    }

    @Test fun rawPlusIsAPlusNotASpace() {
        assertThat(ZimUrls.parse("https://zim.granger.invalid/p/C++")).isEqualTo(ZimUrl("p", "C++"))
    }

    @Test fun keepsFragmentAndDropsQuery() {
        assertThat(ZimUrls.parse("https://zim.granger.invalid/p/Photosynthesis?x=1#s3"))
            .isEqualTo(ZimUrl("p", "Photosynthesis", "s3"))
        assertThat(ZimUrls.forEntry("p", "Photosynthesis", "s3"))
            .isEqualTo("https://zim.granger.invalid/p/Photosynthesis#s3")
    }

    @Test fun malformedEscapesAreKeptLiterally() {
        assertThat(ZimUrls.parse("https://zim.granger.invalid/p/100%_Pure%2")).isEqualTo(ZimUrl("p", "100%_Pure%2"))
    }

    @Test fun emptyPathParses() {
        assertThat(ZimUrls.parse("https://zim.granger.invalid/p/")).isEqualTo(ZimUrl("p", ""))
    }

    @Test fun foreignUrlsAreNotZimUrls() {
        assertThat(ZimUrls.parse("https://example.com/p/Photosynthesis")).isNull()
        assertThat(ZimUrls.parse("https://zim.granger.invalid.evil.com/p/x")).isNull()
        assertThat(ZimUrls.parse("http://zim.granger.invalid/p/x")).isNull()
        assertThat(ZimUrls.parse("https://zim.granger.invalid/")).isNull()
        assertThat(ZimUrls.parse("https://zim.granger.invalid/onlypack")).isNull()
        assertThat(ZimUrls.parse("javascript:alert(1)")).isNull()
    }
}
