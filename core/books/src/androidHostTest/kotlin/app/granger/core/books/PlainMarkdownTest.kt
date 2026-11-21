package app.granger.core.books

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Plan I5 Task 6: the iPhone's Markdown reader ([PlainMarkdown]) cuts the same chapters as Android's commonmark one
 * ([CommonmarkChapters]), with the same titles and the same words, and writes well-formed XHTML.
 */
class PlainMarkdownTest {
    private val samples = listOf(
        "# One\n\nalpha\n\n# Two\n\nbeta ![img](x.png)\n\n## sub\n\ngamma",
        "Intro before any heading.\n\n## Part A\n\nSome *em* and **strong** and `code`.\n\n## Part B\n\n- one\n- two\n  - nested\n- three\n\n1. first\n2. second",
        "# Book\n\nA lead.\n\n## Chapter 1\n\n> A quote\n> over lines\n\nText with a [link](https://example.org \"t\") and <https://granger.app>.\n\n## Chapter 2\n\n```\ncode <here>\n```\n\n---\n\nEnd & done.",
        "Title\n=====\n\nPara one.\n\nSection\n-------\n\nPara two.\n\nSection two\n-----------\n\nPara three.",
        "No headings at all, just a paragraph\nover two lines.\n\nAnd a second one with a hard  \nbreak.",
        "# A\n\n<script>alert(1)</script>\n\n# B\n\n[x](javascript:alert(1)) and \\*not em\\*",
        "# Only one\n\n### Deep\n\ntext\n\n### Deeper\n\nmore",
    )

    private fun words(xhtml: String) = xhtml.replace(Regex("<[^>]+>"), " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()

    @Test fun sameChaptersAndWordsAsCommonmark() {
        for (md in samples) {
            val ours = PlainMarkdown.chapters(md)
            val theirs = CommonmarkChapters.chapters(md)
            assertThat(ours.map { it.first }).isEqualTo(theirs.map { it.first })
            assertThat(ours.map { words(it.second) }).isEqualTo(theirs.map { words(it.second) })
        }
    }

    @Test fun sameTagsAsCommonmark() {
        val tags = Regex("<(h[1-6]|p|em|strong|code|pre|blockquote|ul|ol|li|a|hr|br)\\b")
        for (md in samples) {
            val ours = PlainMarkdown.chapters(md).joinToString("") { it.second }
            val theirs = CommonmarkChapters.chapters(md).joinToString("") { it.second }
            assertThat(tags.findAll(ours).map { it.groupValues[1] }.toList()).isEqualTo(tags.findAll(theirs).map { it.groupValues[1] }.toList())
        }
    }

    @Test fun picturesDroppedScriptsEscapedLinksSanitised() {
        val all = PlainMarkdown.chapters(samples[0] + "\n\n" + samples[5]).joinToString("") { it.second }
        assertThat(all).doesNotContain("<img")
        assertThat(all).doesNotContain("<script")
        assertThat(all.lowercase()).doesNotContain("javascript:")
        assertThat(all).contains("*not em*")
    }

    @Test fun wellFormedXhtml() {
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (md in samples) for ((_, body) in PlainMarkdown.chapters(md)) {
            builder.parse(org.xml.sax.InputSource(java.io.StringReader("<body>$body</body>")))
        }
    }
}
