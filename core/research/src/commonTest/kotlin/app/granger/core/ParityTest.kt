package app.granger.core

import app.granger.core.books.XhtmlChunks
import app.granger.core.books.cutAtSpaces
import app.granger.core.research.ArticleText
import app.granger.core.research.CitedQuote
import app.granger.core.research.Entities
import app.granger.core.research.HtmlPassages
import app.granger.core.research.MediaCues
import app.granger.core.research.YearFacts
import app.granger.core.text.Chunkers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same inputs through the shared text code on the JVM and on iOS (Plan I1, from the I0 trial). The vectors key on
 * chunk hashes, so the two must agree to the byte. GOLDEN is the JVM's output (`:core:research:jvmTest` prints it on a
 * mismatch); `iosSimulatorArm64Test` must then match it.
 */
class ParityTest {
    private val html = """
        <html><body><div id="content"><h2 id="Life">Life</h2>
        <p>Kurt Gödel (1906–1978) was an Austrian&nbsp;logician. Dr. Smith met him in the U.S. in 1940. He worked at the IAS!</p>
        <table><tr><td>skip me</td></tr></table>
        <p>The ﬁrst theorem — “incompleteness” — appeared in 1931.   Was it proved? Yes.</p>
        <h3 id="Work">Work</h3><ul><li>Completeness, 1929</li><li>Constructible universe, 1938</li></ul>
        <p>Ἀριστοτέλης and Ångström, café, naïve; ＦＵＬＬＷＩＤＴＨ ２０２６.</p></div></body></html>
    """.trimIndent()

    private val xhtml = """
        <?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><body>
        <h1>Chapter I</h1><p>It was a dark night. <em>Very</em> dark.</p><blockquote><p>Quoted line, Mr. Holmes.</p></blockquote>
        <p>Last paragraph with a long tail of words to cut here and there and everywhere.</p></body></html>
    """.trimIndent()

    private fun fingerprint(): String = buildString {
        appendLine(listOf("", "Gödel", "café", "ﬁrst", "𝔘𝔫𝔦𝔠𝔬𝔡𝔢", "a b", "日本語").joinToString { Chunkers.hash(it) })
        appendLine(ArticleText.blocks(html))
        appendLine(HtmlPassages.split(html, targetWords = 12, maxWords = 20))
        appendLine(XhtmlChunks.of("OEBPS/c001.xhtml", xhtml.encodeToByteArray()))
        appendLine(cutAtSpaces("Last paragraph with a long tail of words 😀 to cut here", 17))
        appendLine(CitedQuote.fold("Ἀριστοτέλης Ångström ＦＵＬＬ ﬁrst"))
        appendLine(listOf("What symphony movement?", "Who was Gödel?", "when did naïve café open").map { MediaCues.asked(it) to Entities.asked(it) })
        appendLine(Entities.runs("Did Kurt Gödel meet Albert Einstein at Princeton?"))
        appendLine(YearFacts.lifespan("Kurt Gödel (April 28, 1906 – January 14, 1978) was a logician."))
    }

    @Test fun matchesTheJvm() {
        val got = fingerprint()
        if (got != GOLDEN) println("FINGERPRINT=" + got.replace("\\", "\\\\").replace("\n", "\\n"))
        assertEquals(GOLDEN, got)
    }

    /** Plan I1: one sentence splitter in common code (`Sentences`), so both phones quote the same sentence. */
    @Test fun quoteSentenceIsTheSameOnBoth() {
        val quote = CitedQuote.pick("Gödel was a logician who met Dr. Smith in the U.S. [1]", 1, ArticleText.blocks(html).joinToString("\n") { it.text })!!
        assertEquals("Kurt Gödel (1906–1978) was an Austrian logician.", quote.sentence)
    }

    companion object {
        val GOLDEN = """da39a3ee5e6b4b0d, adba6a46f0b4906e, f424452a9673918c, 847e098914030e50, 61247ec3ffd051aa, a3256d02eb06787d, c12140a0ffb4e564
[ArticleBlock(id=Life, tag=h2, text=Life), ArticleBlock(id=g1, tag=p, text=Kurt Gödel (1906–1978) was an Austrian logician. Dr. Smith met him in the U.S. in 1940. He worked at the IAS!), ArticleBlock(id=g2, tag=p, text=The ﬁrst theorem — “incompleteness” — appeared in 1931. Was it proved? Yes.), ArticleBlock(id=Work, tag=h3, text=Work), ArticleBlock(id=g4, tag=li, text=Completeness, 1929), ArticleBlock(id=g5, tag=li, text=Constructible universe, 1938), ArticleBlock(id=g6, tag=p, text=Ἀριστοτέλης and Ångström, café, naïve; ＦＵＬＬＷＩＤＴＨ ２０２６.)]
[HtmlPassage(heading=Life, anchor=Life, text=Kurt Gödel (1906–1978) was an Austrian logician. Dr. Smith met him in, section=Life), HtmlPassage(heading=Life, anchor=Life, text=the U.S. in 1940. He worked at the IAS!, section=Life), HtmlPassage(heading=Life, anchor=Life, text=The ﬁrst theorem — “incompleteness” — appeared in 1931. Was it proved? Yes., section=Life), HtmlPassage(heading=Work, anchor=Work, text=Completeness, 1929
Constructible universe, 1938
Ἀριστοτέλης and Ångström, café, naïve; ＦＵＬＬＷＩＤＴＨ ２０２６., section=Life)]
[TextChunk(href=OEBPS/c001.xhtml, ordinal=0, progression=0.0, text=Chapter I
It was a dark night. Very dark.
Quoted line, Mr. Holmes.
Last paragraph with a long tail of words to cut here and there and everywhere.)]
[Last paragraph, with a long tail, of words 😀 to, cut here]
αριστοτελης angstrom ｆｕｌｌ ﬁrst
[(true, false), (false, false), (false, false)]
[Kurt Gödel, Albert Einstein, Princeton]
(28 April 1906, 14 January 1978)
"""
    }
}
