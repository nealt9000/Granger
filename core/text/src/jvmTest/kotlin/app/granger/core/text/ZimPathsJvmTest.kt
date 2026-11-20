package app.granger.core.text

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared [ZimPaths] gives what Android's `java.net.URI` version gave, on the links and pictures pages carry. */
class ZimPathsJvmTest {
    private fun viaUri(ref: String, articlePath: String): String {
        val clean = ref.substringBefore('#').substringBefore('?')
        val base = runCatching { URI(null, null, "/$articlePath", null) }.getOrNull()
        val relative = runCatching { URI(clean.replace(" ", "%20")) }.getOrNull()?.let { if (it.isAbsolute) URI("./" + it.toString()) else it }
        val resolved = runCatching { relative?.let { base?.resolve(it) } }.getOrNull()
        val path = resolved?.path?.removePrefix("/") ?: clean.removePrefix("./")
        return path.replace("%20", " ")
    }

    private val refs = listOf(
        "../I/x.jpg", "I/x.jpg", "./I/x.jpg", "x%20y.png", "Cat", "Cat#History", "Cat?x=1", "", "#top", "?a",
        "Halo:_Reach", "File:Cat.jpg", "http://example.com/x", "https://upload.wikimedia.org/a/b.png", "//upload.wikimedia.org/a/b.png",
        "/I/x.jpg", "../../I/x.jpg", "../../../x", "a/./b/../c", "a//b", "a/b/..", "a/b/.", ".", "..", "./", "../",
        "Caf%C3%A9", "Café", "São_Paulo", "%E2%80%93", "100%", "%zz", "a b c", "x\"y", "x<y>", "x{y}", "x|y", "x[1]", "x^y", "x`y",
        "Ünïcödé/../Ä", "日本", "%2520", "a%2Fb", "x:y/z", "1:2", "-x:y", "C++", "AC/DC", "a?b#c", "A/Cat",
    )
    private val bases = listOf("A/Cat", "Cat", "A/B/C", "Café", "a b", "x%y", "Halo:_Reach", "A/Ünï", "a#b", "")

    @Test fun matchesUri() {
        val wrong = ArrayList<String>()
        for (b in bases) for (r in refs) {
            val want = viaUri(r, b)
            val got = ZimPaths.resolve(r, b)
            if (want != got) wrong += "resolve(\"$r\", \"$b\"): uri=\"$want\" shared=\"$got\""
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
    }
}

/** [percentDecoded] gives what `URLDecoder` gave the picture rule (with "+" escaped first). */
class PercentDecodedJvmTest {
    @Test fun matchesUrlDecoder() {
        val cases = listOf("Cat.jpg", "Caf%C3%A9.jpg", "a+b.png", "100%", "%zz", "%E2%80%93x", "%C3", "x%2Fy", "%25", "%2520", "Ünï", "")
        for (s in cases) {
            val want = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
            assertEquals(want, percentDecoded(s) ?: s, s)
        }
    }
}
