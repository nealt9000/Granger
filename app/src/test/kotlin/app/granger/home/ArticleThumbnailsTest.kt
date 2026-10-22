package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArticleThumbnailsTest {
    @Test fun theLeadImageIsResolvedAgainstTheArticleInEitherLayout() {
        val html = """<body><img src="../I/icon.svg"><img class="mw-logo-icon" src="../I/logo.png">
            <img src="data:image/png;base64,AAAA"><img src="../I/m/Cat_poster.jpg" width="220" height="330"></body>"""
        assertThat(ArticleThumbnails.candidates(html, "A/Cat")).containsExactly("I/m/Cat_poster.jpg", "I/Cat_poster.jpg").inOrder()
        assertThat(ArticleThumbnails.candidates("<img src=\"I/m/Cat_poster.jpg\">", "Cat")).containsExactly("I/m/Cat_poster.jpg", "I/Cat_poster.jpg").inOrder()
        // A percent-encoded source resolves to the spaced path; the bare fallback keeps the name as written.
        assertThat(ArticleThumbnails.candidates("<img src=\"./I/x%20y.jpg\">", "Cat")).containsExactly("I/x y.jpg", "I/x%20y.jpg").inOrder()
    }

    @Test fun tinyImagesAndPagesWithoutOneGiveNothing() {
        assertThat(ArticleThumbnails.candidates("<img src=\"I/dot.png\" width=\"12\" height=\"12\">", "Cat")).isEmpty()
        assertThat(ArticleThumbnails.candidates("<p>No pictures</p>", "Cat")).isEmpty()
    }
}
