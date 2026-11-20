package app.granger.online

import app.granger.core.online.WikimediaClient
import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.downloads.LocalOnly
import app.granger.core.downloads.NetworkMode
import app.granger.core.library.KeptArticle
import app.granger.core.library.KeptArticleDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan O2 Tasks 1 and 3: Saved for now, kept articles, and the order an online article is read in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineStoresTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L

    private class FakeDao : KeptArticleDao {
        val rows = MutableStateFlow<List<KeptArticle>>(emptyList())
        override fun observeAll(): Flow<List<KeptArticle>> = rows
        override suspend fun all() = rows.value
        override suspend fun get(lang: String, title: String) = rows.value.firstOrNull { it.lang == lang && it.title == title }
        override suspend fun put(article: KeptArticle) { rows.value = rows.value.filterNot { it.lang == article.lang && it.title == article.title } + article }
        override suspend fun delete(lang: String, title: String) { rows.value = rows.value.filterNot { it.lang == lang && it.title == title } }
    }

    private val knowth by lazy { javaClass.classLoader!!.getResource("online/knowth.html")!!.readText() }
    private var mode = NetworkMode.ONLINE
    private val asked = mutableListOf<String>()

    /** Wikimedia, faked: Knowth's page, and a tiny picture for any thumbnail. */
    private fun wiki(): WikimediaClient {
        val server = Interceptor { chain ->
            val url = chain.request().url.toString()
            if (mode != NetworkMode.ONLINE) throw LocalOnly(chain.request().url.host, mode)
            asked += url
            val (code, body, type) = when {
                "/page/Knowth/html" in url -> Triple(200, knowth, "text/html")
                "wikimedia.org" in url -> Triple(200, "PNG", "image/png")
                else -> Triple(404, "", "text/plain")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(code).body(body.toResponseBody(type.toMediaType())).build()
        }
        return WikimediaClient(OkHttpClient.Builder().addInterceptor(server).build())
    }

    private fun stores(scope: TestScope, savePictures: Boolean = true, capBytes: Long = 300L shl 20): OfflineStores {
        val dao = FakeDao()
        return OfflineStores(
            SavedStore(tmp.newFolder("saved")) { now },
            KeptStore(tmp.newFolder("kept"), dao, scope.backgroundScope) { now },
            savePictures = { savePictures }, capBytes = { capBytes }, clock = { now },
        )
    }

    @Test fun savedForNowPutsGetsAndTrimsOldestOpenedFirst() {
        val s = SavedStore(tmp.newFolder("s")) { now }
        s.put("a", SavedStore.Kind.PAGE, ByteArray(400), "text/html")
        now += 10; s.put("b", SavedStore.Kind.PAGE, ByteArray(400), "text/html")
        now += 10; s.put("c", SavedStore.Kind.PAGE, ByteArray(400), "text/html")
        now += 10; assertThat(s.get("a")!!.bytes.size).isEqualTo(400)   // a is opened again: b is now the oldest
        assertThat(s.size()).isEqualTo(1200)
        s.trim(900)
        assertThat(s.has("b")).isFalse()
        assertThat(s.has("a")).isTrue()
        assertThat(s.has("c")).isTrue()
        assertThat(s.size()).isEqualTo(800)
        s.clear()
        assertThat(s.size()).isEqualTo(0)
        assertThat(s.get("a")).isNull()
    }

    @Test fun anArticleReadOnlineIsSavedAndOpensWithNoSignal() = runTest(UnconfinedTestDispatcher()) {
        val st = stores(this)
        val a = OnlineWikipediaArchive("en", wiki(), stores = st)
        val first = a.read("Knowth")!!
        assertThat(st.saved.has(SavedStore.pageKey("en", "Knowth"))).isTrue()
        // Within the hour: from Saved for now, no request.
        asked.clear()
        assertThat(String(a.read("Knowth")!!.bytes)).isEqualTo(String(first.bytes))
        assertThat(asked).isEmpty()
        // Hours later with no signal: still there.
        now += 5 * SavedStore.FRESH_MS; mode = NetworkMode.LOCAL_ONLY
        assertThat(a.read("Knowth")).isNotNull()
    }

    @Test fun textOnlySavesNoPictures() = runTest(UnconfinedTestDispatcher()) {
        val st = stores(this, savePictures = false)
        val a = OnlineWikipediaArchive("en", wiki(), stores = st)
        assertThat(a.read("_img/upload.wikimedia.org/wikipedia/commons/a/ab/X.png")).isNotNull()
        assertThat(st.saved.has(SavedStore.pictureKey("_img/upload.wikimedia.org/wikipedia/commons/a/ab/X.png"))).isFalse()
    }

    @Test fun keepingASavedArticleAsksNothingAndAKeptFullCopyWinsEvenOnline() = runTest(UnconfinedTestDispatcher()) {
        val st = stores(this)
        val a = OnlineWikipediaArchive("en", wiki(), stores = st)
        val keeper = Keeper({ a }, st.kept, withPictures = { true })
        a.read("Knowth")
        val pictures = OnlineWikipediaArchive.picturesOf(String(a.read("Knowth")!!.bytes))
        assertThat(pictures).isNotEmpty()
        pictures.forEach { a.read(it) }   // the reader showed them: saved for now
        asked.clear()
        val row = keeper.keep("en", "Knowth")!!
        assertThat(asked.filterNot { "summary" in it }).isEmpty()
        assertThat(row.withPictures).isTrue()
        assertThat(st.kept.page("en", "Knowth")).isNotNull()
        assertThat(pictures.all { st.kept.hasPicture(it) }).isTrue()
        // Saved for now emptied, the gate shut: the kept copy and its pictures still open.
        st.saved.clear(); mode = NetworkMode.LOCAL_ONLY
        assertThat(a.read("Knowth")).isNotNull()
        assertThat(a.read(pictures.first())).isNotNull()
        // Removed: its pictures go with it.
        keeper.remove("en", "Knowth")
        assertThat(st.kept.get("en", "Knowth")).isNull()
        assertThat(st.kept.hasPicture(pictures.first())).isFalse()
    }

    @Test fun aTextOnlyKeepLosesToOnlineAndWinsWithNoSignal() = runTest(UnconfinedTestDispatcher()) {
        val st = stores(this, savePictures = false)
        val a = OnlineWikipediaArchive("en", wiki(), stores = st)
        val row = Keeper({ a }, st.kept, withPictures = { false }).keep("en", "Knowth")!!
        assertThat(row.withPictures).isFalse()
        st.saved.clear()
        asked.clear()
        a.read("Knowth")
        assertThat(asked.single()).contains("/page/Knowth/html")   // online wins over the shorter copy
        st.saved.clear(); mode = NetworkMode.LOCAL_ONLY
        assertThat(a.read("Knowth")).isNotNull()                  // and the shorter copy serves when online can't
    }

    @Test fun summariesAreSavedForNow() = runTest(UnconfinedTestDispatcher()) {
        val st = stores(this)
        val s = WikimediaClient.Summary("Moon", "Moon", "The Moon is…", null, "https://upload.wikimedia.org/x.jpg", 405, 7)
        assertThat(WikimediaClient.summaryFromJson(WikimediaClient.summaryToJson(s))).isEqualTo(s)
        st.saved.put(SavedStore.summaryKey("en", "Moon"), SavedStore.Kind.SUMMARY, WikimediaClient.summaryToJson(s).toByteArray(), "application/json")
        mode = NetworkMode.LOCAL_ONLY
        assertThat(OnlineWikipediaArchive("en", wiki(), stores = st).summary("Moon")).isEqualTo(s)
    }

    @Test fun availabilityKnowsSavedAndKept() = runTest {
        val web = ArticleCopy("wikipedia_en_online", "Knowth", ArticleCopy.Kind.ONLINE)
        assertThat(Availability.of(listOf(web), saved = true)).isEqualTo(Availability.Saved)
        assertThat(Availability.of(listOf(web, ArticleCopy("wikipedia_en_online", "Knowth", ArticleCopy.Kind.FULL)), saved = true))
            .isEqualTo(Availability.OnPhone(full = true))
        val copies = ArticleCopies({ app.granger.core.library.QidIndexes(emptyList()) }, { listOf(OnlineSources.row("en")) }, { true }, { setOf("en") },
            kept = { _, t -> if (t == "Knowth") false else null })
        assertThat(copies.choose("wikipedia_en_online", "Knowth").kind).isEqualTo(ArticleCopy.Kind.ONLINE)   // shorter loses online
        val off = ArticleCopies({ app.granger.core.library.QidIndexes(emptyList()) }, { listOf(OnlineSources.row("en")) }, { false }, { setOf("en") },
            kept = { _, t -> if (t == "Knowth") false else null })
        assertThat(off.choose("wikipedia_en_online", "Knowth").kind).isEqualTo(ArticleCopy.Kind.SHORT)
    }
}
