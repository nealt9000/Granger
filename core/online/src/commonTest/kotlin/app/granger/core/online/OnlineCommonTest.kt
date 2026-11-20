package app.granger.core.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The online parsers and the page transform, on the JVM and iOS alike (Plan I1). */
class OnlineCommonTest {
    @Test fun summaryParses() {
        val json = """{"type":"standard","title":"Moon","titles":{"canonical":"Moon","normalized":"Moon"},
            "extract":"The Moon is Earth's only natural satellite.","description":"Natural satellite of Earth",
            "thumbnail":{"source":"https://upload.wikimedia.org/a/b/Moon.jpg?x=1"},"wikibase_item":"Q405","revision":"123"}"""
        val s = WikimediaClient.summaryOf(parseObject(json)!!)!!
        assertEquals("Moon", s.key)
        assertEquals(405L, s.qid)
        assertEquals(123L, s.revision)
        assertEquals("https://upload.wikimedia.org/a/b/Moon.jpg", s.thumbnail)
        assertEquals(s, WikimediaClient.summaryFromJson(WikimediaClient.summaryToJson(s)))
        assertNull(WikimediaClient.summaryOf(parseObject("""{"type":"disambiguation","title":"X","extract":""}""")!!))
    }

    @Test fun batchFollowsNormalisationsAndRedirects() {
        val json = """{"query":{"normalized":[{"from":"moon","to":"Moon"}],"redirects":[{"from":"Luna","to":"Moon"}],
            "pages":[{"title":"Moon","extract":"Knowth (; Irish: Cnóbha) is","pageprops":{"wikibase_item":"Q405"}},{"title":"Nope","missing":true}]}}"""
        val got = WikimediaClient.batchOf(json, listOf("moon", "Luna", "Nope"))
        assertEquals(setOf("moon", "Luna"), got.keys)
        assertEquals("Knowth (Irish: Cnóbha) is", got.getValue("moon").extract)
    }

    @Test fun hitsAndRelated() {
        val hits = WikimediaClient.hitsOf("""{"pages":[{"key":"Moon","title":"Moon","description":null,"excerpt":"the <span class=\"searchmatch\">Moon</span> &amp; sky"}]}""")
        assertEquals("the Moon & sky", hits.single().excerpt)
        assertNull(hits.single().description)
        val related = WikimediaClient.relatedOf("""{"query":{"pages":[{"title":"B b","index":2},{"title":"A","index":1,"pageprops":{"wikibase_item":"Q1"}}]}}""")
        assertEquals(listOf("A", "B_b"), related.map { it.key })
        assertEquals(1L, related.first().qid)
    }

    @Test fun keysAndPictures() {
        assertEquals("AC%2FDC", WikimediaClient.enc("AC/DC"))
        assertEquals("Albert Einstein", WikimediaClient.titleOfKey("Albert_Einstein"))
        assertEquals("Café", WikimediaClient.titleOfKey("Caf%C3%A9"))
        assertEquals("Moon,_full.jpg", OnlineWikipediaArchive.fileName("_assets_/x/Moon%252C_full.jpg"))
        // Commons keys a file's folders by the MD5 of its name.
        assertTrue(OnlineWikipediaArchive.commonsThumbUrl("I/Example.jpg", 960).startsWith("https://upload.wikimedia.org/wikipedia/commons/thumb/a/a9/Example.jpg/960px-"))
    }

    @Test fun transformMakesAReaderPage() {
        val html = """<html><head><base href="//en.wikipedia.org/wiki/"><link rel="stylesheet" href="x.css"><script>1</script></head>
            <body><p><a href="./Earth">Earth</a> <a href="//commons.wikimedia.org/x">c</a></p>
            <img src="//upload.wikimedia.org/a/Moon.jpg?x=1" srcset="//upload.wikimedia.org/a/Moon2x.jpg 2x"></body></html>"""
        val out = OnlineWikipediaArchive.transform(html, "Moon")
        assertTrue("href=\"Earth\"" in out, out)
        assertTrue("https://commons.wikimedia.org/x" in out, out)
        assertTrue("src=\"_img/upload.wikimedia.org/a/Moon2x.jpg\"" in out, out)
        assertTrue("<script" !in out && "<base" !in out && "x.css" !in out, out)
        assertTrue("<h1>Moon</h1>" in out, out)
        assertEquals(listOf("_img/upload.wikimedia.org/a/Moon2x.jpg"), OnlineWikipediaArchive.picturesOf(out))
    }
}
