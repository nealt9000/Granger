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

    private val wheel = """<table class="sidebar nomobile"><tr><td class="sidebar-image"><img src="../I/Dharma_Wheel.svg.png" width="100" height="100" data-file-type="drawing"></td></tr></table>"""
    private fun photo(name: String, w: Int = 220, h: Int = 160) = """<figure><img src="../I/$name" width="$w" height="$h" data-file-type="bitmap"></figure>"""

    /** Plan EX P0, the rule Neal accepted (v4): a series sidebar's drawn symbol gives way to the first roomy JPEG photo. */
    @Test fun aSeriesSidebarsSymbolGivesWayToTheFirstPhotoWideBeforeTall() {
        val html = wheel + photo("Monk.jpg", 160, 240) + photo("Temple.jpg") + photo("Later.jpg")
        assertThat(ArticleThumbnails.candidates(html, "A/Noble_Eightfold_Path").take(4))
            .containsExactly("I/Temple.jpg", "I/Dharma_Wheel.svg.png", "I/Monk.jpg", "I/Later.jpg").inOrder()
    }

    @Test fun theSymbolStandsWhenTheArticleIsAboutASymbolOrNoPhotoIsFit() {
        val first = { html: String, path: String -> ArticleThumbnails.candidates(html, path).first() }
        // The topic is the symbol.
        assertThat(first(wheel + photo("Temple.jpg"), "A/Buddhist_symbols")).isEqualTo("I/Dharma_Wheel.svg.png")
        // Only a small photo, a PNG screenshot, a map by name and a drawing follow: none takes its place.
        val unfit = wheel + photo("Small.jpg", 100, 80) + photo("Screen.png") + photo("Location_map_of_India.jpg") +
            """<figure><img src="../I/Plan.jpg" width="220" height="160" data-file-type="drawing"></figure>"""
        assertThat(first(unfit, "A/Noble_Eightfold_Path")).isEqualTo("I/Dharma_Wheel.svg.png")
        // A photograph in a sidebar, an infobox's drawing and a figure's drawing all stand as the first picture.
        val sidebarPhoto = """<table class="sidebar"><tr><td><img src="../I/Buddha.jpg" width="200" height="260" data-file-type="bitmap"></td></tr></table>"""
        assertThat(first(sidebarPhoto + photo("Temple.jpg"), "A/Noble_Eightfold_Path")).isEqualTo("I/Buddha.jpg")
        val infobox = """<table class="infobox"><tr><td class="infobox-image"><img src="../I/Logo.png" width="200" height="120" data-file-type="drawing"></td></tr></table>"""
        assertThat(first(infobox + photo("Office.jpg"), "A/Sega")).isEqualTo("I/Logo.png")
        // A word inside another word is not the word: "Uncharted" has no chart.
        assertThat(first(wheel + photo("Uncharted_waters.jpg"), "A/Noble_Eightfold_Path")).isEqualTo("I/Uncharted_waters.jpg")
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

    @Test fun theTopOfALongArticleGivesTheSamePictureAndTheSameSummaryAsTheWhole() {
        fun img(name: String, w: Int = 220, h: Int = 160, extra: String = "") = "<figure><img src=\"../I/$name\" width=\"$w\" height=\"$h\" $extra></figure>"
        val lead = "<html><head><title>Airplane</title></head><body><p>An airplane is a fixed-wing aircraft. It flies. It lands.</p>" +
            "<table class=\"sidebar\"><tr><td><img src=\"../I/Symbol.svg.png\" width=\"100\" height=\"100\" data-file-type=\"drawing\"></td></tr></table>"
        val icons = (1..6).joinToString("") { "<img src=\"../I/icon$it.png\" width=\"20\" height=\"20\">" } + "<img class=\"mwe-math-fallback\" src=\"../I/f.svg\">"
        val sections = (1..40).joinToString("") { "<h2>Section $it</h2><p>${"Words about section $it. ".repeat(60)}</p>" + img("Photo$it.jpg") + icons }
        val html = lead + sections + "</body></html>"
        val top = ArticleThumbnails.head(html)
        assertThat(top.length).isLessThan(html.length / 2)
        assertThat(ArticleThumbnails.candidates(top, "A/Airplane")).isEqualTo(ArticleThumbnails.candidates(html, "A/Airplane"))
        assertThat(LeadLinks.parse(top, "A/Airplane").summary).isEqualTo(LeadLinks.parse(html, "A/Airplane").summary)
        assertThat(LeadLinks.parse(top, "A/Airplane").title).isEqualTo("Airplane")
        // Short, without a heading, or with few pictures: the whole of it.
        assertThat(ArticleThumbnails.head(lead)).isEqualTo(lead)
        val few = lead + "<h2>One</h2>" + "<p>${"Long. ".repeat(10_000)}</p>" + img("Only.jpg")
        assertThat(ArticleThumbnails.head(few)).isEqualTo(few)
        // A tag it can't read for certain isn't counted, so the cut only comes later.
        val loose = lead + (1..40).joinToString("") { "<h2>S$it</h2><p>${"Words. ".repeat(300)}</p><img src='../I/P$it.jpg' width=220 height=160>" }
        assertThat(ArticleThumbnails.head(loose)).isEqualTo(loose)
    }
}
