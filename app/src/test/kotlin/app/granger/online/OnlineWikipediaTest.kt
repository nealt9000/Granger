package app.granger.online

import app.granger.core.zim.LinkAction
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan O1 Tasks 3 and 4: an online article as the reader gets it, and the links around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineWikipediaTest {
    private val knowth by lazy { javaClass.classLoader!!.getResource("online/knowth.html")!!.readText() }

    @Test fun theTransformMakesAPackShapedPage() {
        val doc = Jsoup.parse(OnlineWikipediaArchive.transform(knowth, "Knowth"))
        assertThat(doc.select("base, script, link[rel~=mw:|dc:], meta[property], span.mw-editsection")).isEmpty()
        // Wikipedia's skin is replaced by Granger's, served from the app.
        assertThat(doc.select("link[rel=stylesheet]").map { it.attr("href") }).containsExactly(ZimUrls.forEntry("_granger", "online.css"))
        assertThat(doc.selectFirst("meta[name=color-scheme]")!!.attr("content")).isEqualTo("light dark")
        assertThat(doc.selectFirst("html")!!.hasClass("skin-theme-clientpref-os")).isTrue()
        // Links as in a ZIM; no protocol-relative links left.
        val hrefs = doc.select("a[href]").map { it.attr("href") }
        assertThat(hrefs.none { it.startsWith("./") || it.startsWith("//") }).isTrue()
        assertThat(hrefs).contains("Newgrange")
        // Pictures through the reader's interceptor, at 2x, without the tracking query or srcset.
        val srcs = doc.select("img[src]").map { it.attr("src") }
        assertThat(srcs).isNotEmpty()
        assertThat(srcs.filter { it.contains("wikimedia.org") }.all { it.startsWith(OnlineWikipediaArchive.IMG) && '?' !in it }).isTrue()
        assertThat(srcs.first { it.contains("Knowth_-_2021") }).contains("/960px-")
        assertThat(doc.select("img[srcset]")).isEmpty()
        assertThat(doc.title()).isEqualTo("Knowth")
        assertThat(doc.select("h1")).isNotEmpty()
    }

    @Test fun indexPictureNamesBecomeTheWikisFileNames() {
        val a = "_assets_/d1f33b1cb42dc60ec0e42c6954875701/"
        assertThat(OnlineWikipediaArchive.fileName(a + "Crude_death_rate%252C_World%252C_2023_(cropped).svg.png")).isEqualTo("Crude_death_rate,_World,_2023_(cropped).svg")
        assertThat(OnlineWikipediaArchive.fileName(a + "%C3%89douard_Manet_-_Le_Suicid%C3%A9_(ca._1877).jpg")).isEqualTo("Édouard_Manet_-_Le_Suicidé_(ca._1877).jpg")
        assertThat(OnlineWikipediaArchive.fileName("I/FullMoon2010.jpg.webp")).isEqualTo("FullMoon2010.jpg")
        assertThat(OnlineWikipediaArchive.fileName(a + "Placid_death.JPG")).isEqualTo("Placid_death.JPG")
        assertThat(OnlineWikipediaArchive.filePathUrl("en", a + "Wiertz_burial.jpg", 800))
            .isEqualTo("https://en.wikipedia.org/wiki/Special:FilePath/Wiertz_burial.jpg?width=800")
        assertThat(OnlineWikipediaArchive.isIndexPicture(a + "Wiertz_burial.jpg")).isTrue()
        assertThat(OnlineWikipediaArchive.isIndexPicture("I/O")).isFalse()
        assertThat(OnlineWikipediaArchive.isIndexPicture("Moon")).isFalse()
    }

    @Test fun askingWhatAPathIsNeverFetches() {
        val archive = OnlineWikipediaArchive("en", WikimediaClient(OkHttpClient(), onMainThread = { true }))
        // The client throws if it's asked anything on this "main thread"; entry() and the link check must not ask.
        assertThat(archive.entry("AC/DC").mimeType).isEqualTo("text/html")
        assertThat(archive.entry("AC/DC").title).isEqualTo("AC/DC")
        assertThat(archive.entry("_img/upload.wikimedia.org/a/b/X.png").mimeType).isEqualTo("image/png")
        val resolver = ZimResolver({ archive })
        assertThat(resolver.linkAction(ZimUrls.forEntry("wikipedia_en_online", "Newgrange"))).isEqualTo(LinkAction.Load)
    }

    @Test fun onlineSourceIdsCarryTheirLanguage() {
        assertThat(OnlineWikipediaArchive.languageOf("wikipedia_es_online")).isEqualTo("es")
        assertThat(OnlineWikipediaArchive.languageOf("wikipedia_en_top_mini")).isNull()
        assertThat(OnlineSources.isOnline("wikipedia_en_online")).isTrue()
    }

    private fun sources(enabled: Boolean = true, langs: List<String> = listOf("en", "es")) =
        OnlineSources(WikimediaClient(OkHttpClient()), flowOf(langs), { langs }, { enabled })

    @Test fun wikipediaLinksOpenOnlineInTheReader() {
        val s = sources()
        assertThat(s.inApp("https://en.wikipedia.org/wiki/Moon")).isEqualTo(ZimUrls.forEntry("wikipedia_en_online", "Moon"))
        assertThat(s.inApp("https://es.m.wikipedia.org/wiki/Luna#Historia")).isEqualTo(ZimUrls.forEntry("wikipedia_es_online", "Luna", "Historia"))
        assertThat(s.inApp("https://en.wikipedia.org/wiki/Star_Wars:_Episode_IV")).isNotNull()
        assertThat(s.inApp("https://en.wikipedia.org/wiki/Brú_na_Bóinne".replace("ú", "%C3%BA").replace("ó", "%C3%B3")))
            .isEqualTo(ZimUrls.forEntry("wikipedia_en_online", "Brú_na_Bóinne"))
        // Other Wikipedias, other namespaces and other sites stay outside.
        assertThat(s.inApp("https://de.wikipedia.org/wiki/Mond")).isNull()
        assertThat(s.inApp("https://en.wikipedia.org/wiki/File:Moon.jpg")).isNull()
        assertThat(s.inApp("https://en.wikipedia.org/wiki/Special:Random")).isNull()
        assertThat(s.inApp("https://en.wikipedia.org/w/index.php?title=Moon&action=edit")).isNull()
        assertThat(s.inApp("https://example.com/wiki/Moon")).isNull()
        assertThat(sources(enabled = false).inApp("https://en.wikipedia.org/wiki/Moon")).isNull()
        // The reader redirects such a link instead of leaving the app.
        val resolver = ZimResolver({ null }, inApp = s::inApp)
        assertThat(resolver.linkAction("https://en.wikipedia.org/wiki/Moon")).isEqualTo(LinkAction.Redirect(ZimUrls.forEntry("wikipedia_en_online", "Moon")))
        assertThat(resolver.linkAction("https://example.com/")).isEqualTo(LinkAction.External("https://example.com/"))
    }

    @Test fun onlineSourcesFollowTheLanguagesAndTheFlag() {
        assertThat(sources().archives().keys).containsExactly("wikipedia_en_online", "wikipedia_es_online")
        assertThat(sources(enabled = false).archives()).isEmpty()
        val row = OnlineSources.row("es")
        assertThat(row.title).isEqualTo("Wikipedia")
        assertThat(row.filePath).isEmpty()
        assertThat(app.granger.home.WanderModel.encyclopediasOf(listOf(row), listOf("es"))).containsExactly(row)
    }
}
