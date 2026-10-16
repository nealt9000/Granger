package app.granger.core.zim

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZimResolverTest {
    private val archive = FakeZimArchive(
        entries = mapOf(
            "index" to ("text/html" to "<h1>Home</h1>"),
            "Photosynthesis" to ("text/html; charset=iso-8859-1" to "<h1>P</h1>"),
            "C++" to ("text/html" to "<h1>C++</h1>"),
            "style.css" to ("text/css" to "body{}"),
            "leaf.png" to ("image/png" to "PNG"),
            "app.js" to ("application/javascript" to "1"),
        ),
        redirects = mapOf("Plants" to "Photosynthesis"),
    )
    private val resolver = ZimResolver { id -> archive.takeIf { id == "p" } }

    private fun content(url: String) = resolver.response(url) as ZimResponse.Content

    @Test fun servesEntriesWithMimeTypeAndEncoding() {
        val html = content(ZimUrls.forEntry("p", "index"))
        assertThat(html.mimeType).isEqualTo("text/html")
        assertThat(html.encoding).isEqualTo("UTF-8")
        assertThat(String(html.bytes)).isEqualTo("<h1>Home</h1>")

        assertThat(content(ZimUrls.forEntry("p", "style.css")).encoding).isEqualTo("UTF-8")
        assertThat(content(ZimUrls.forEntry("p", "app.js")).encoding).isEqualTo("UTF-8")
        val png = content(ZimUrls.forEntry("p", "leaf.png"))
        assertThat(png.mimeType).isEqualTo("image/png")
        assertThat(png.encoding).isNull()
    }

    @Test fun usesTheCharsetTheZimDeclares() {
        val page = content(ZimUrls.forEntry("p", "Photosynthesis"))
        assertThat(page.mimeType).isEqualTo("text/html")
        assertThat(page.encoding).isEqualTo("iso-8859-1")
    }

    @Test fun servesRedirectTargets() {
        assertThat(String(content(ZimUrls.forEntry("p", "Plants")).bytes)).isEqualTo("<h1>P</h1>")
    }

    @Test fun servesPathsWithSpecialCharacters() {
        assertThat(String(content("https://zim.granger.invalid/p/C%2B%2B").bytes)).isEqualTo("<h1>C++</h1>")
    }

    @Test fun missingEntriesAndUnknownPacksAreNotFound() {
        assertThat(resolver.response(ZimUrls.forEntry("p", "Nope"))).isEqualTo(ZimResponse.NotFound)
        assertThat(resolver.response(ZimUrls.forEntry("other", "index"))).isEqualTo(ZimResponse.NotFound)
        assertThat(resolver.response("https://zim.granger.invalid/p/")).isEqualTo(ZimResponse.NotFound)
    }

    @Test fun everythingElseIsBlocked() {
        assertThat(resolver.response("https://example.com/tracker.js")).isEqualTo(ZimResponse.Blocked)
        assertThat(resolver.response("http://zim.granger.invalid/p/index")).isEqualTo(ZimResponse.Blocked)
        assertThat(resolver.response("file:///sdcard/secret.txt")).isEqualTo(ZimResponse.Blocked)
    }

    @Test fun linksInsideThePackLoadNormally() {
        assertThat(resolver.linkAction(ZimUrls.forEntry("p", "C++"))).isEqualTo(LinkAction.Load)
        assertThat(resolver.linkAction(ZimUrls.forEntry("p", "Missing"))).isEqualTo(LinkAction.Load)
    }

    @Test fun redirectLinksLoadTheCanonicalUrlAndKeepTheFragment() {
        assertThat(resolver.linkAction(ZimUrls.forEntry("p", "Plants", "s2")))
            .isEqualTo(LinkAction.Redirect(ZimUrls.forEntry("p", "Photosynthesis", "s2")))
    }

    @Test fun webAndMailLinksLeaveTheApp() {
        assertThat(resolver.linkAction("https://example.com/")).isEqualTo(LinkAction.External("https://example.com/"))
        assertThat(resolver.linkAction("mailto:a@b.c")).isEqualTo(LinkAction.External("mailto:a@b.c"))
    }

    @Test fun unsafeSchemesAreIgnored() {
        assertThat(resolver.linkAction("javascript:alert(1)")).isEqualTo(LinkAction.Ignore)
        assertThat(resolver.linkAction("file:///sdcard/x")).isEqualTo(LinkAction.Ignore)
        assertThat(resolver.linkAction("intent://scan/#Intent;scheme=zxing;end")).isEqualTo(LinkAction.Ignore)
    }
}
