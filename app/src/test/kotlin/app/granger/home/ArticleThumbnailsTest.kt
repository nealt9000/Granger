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

    @Test fun pruneKeepsTheMostRecentlyShownUnderTheCap() {
        val dir = kotlin.io.path.createTempDirectory("thumbs").toFile()
        try {
            val now = System.currentTimeMillis()
            // Five 100-byte thumbnails, a.jpg shown longest ago; a stray .tmp from a crash.
            val files = listOf("a", "b", "c", "d", "e").mapIndexed { i, n ->
                java.io.File(dir, "$n.jpg").apply { writeBytes(ByteArray(100)); setLastModified(now - (5 - i) * 60_000L) }
            }
            java.io.File(dir, "x.jpg.tmp").apply { writeBytes(ByteArray(10)); setLastModified(now - 10 * 60_000L) }
            java.io.File(dir, "y.jpg.tmp").writeBytes(ByteArray(10))    // being written right now: kept
            ArticleThumbnails(dir).prune(maxBytes = 300)
            assertThat(dir.list()!!.sorted()).containsExactly("c.jpg", "d.jpg", "e.jpg", "y.jpg.tmp").inOrder()
            assertThat(files[0].exists()).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
}
