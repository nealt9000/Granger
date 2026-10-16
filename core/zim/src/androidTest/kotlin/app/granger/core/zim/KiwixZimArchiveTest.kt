package app.granger.core.zim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class KiwixZimArchiveTest {
    private lateinit var archive: ZimArchive

    @Before fun open() { archive = TestZim.open() }
    @After fun close() { archive.close() }

    private fun html(path: String) = String(archive.read(path)!!.bytes)

    @Test fun readsMetadataAndMainPage() {
        assertThat(archive.metadata.title).isEqualTo("Granger Test Library")
        assertThat(archive.metadata.language).isEqualTo("eng")
        assertThat(archive.metadata.date).isEqualTo("2026-10-15")
        assertThat(archive.metadata.name).isEqualTo("granger_test_en")
        assertThat(archive.mainPath).isEqualTo("index")
        assertThat(archive.hasFulltextIndex).isTrue()
        assertThat(archive.uuid).hasLength(36)
    }

    @Test fun readsHtmlCssAndImages() {
        val index = archive.read("index")!!
        assertThat(index.mimeType).isEqualTo("text/html")
        assertThat(String(index.bytes)).contains("Start here.")
        assertThat(archive.read("style.css")!!.mimeType).isEqualTo("text/css")
        val png = archive.read("leaf.png")!!
        assertThat(png.mimeType).isEqualTo("image/png")
        assertThat(png.bytes.copyOfRange(1, 4)).isEqualTo("PNG".toByteArray())
    }

    @Test fun readsPathsWithSpecialCharacters() {
        assertThat(html("C++")).contains("<title>C++</title>")
        assertThat(html("What_is_this?")).contains("<title>What is this?</title>")
        assertThat(html("Café_au_lait")).contains("<title>Café au lait</title>")
    }

    @Test fun followsRedirects() {
        assertThat(archive.resolvePath("Plants")).isEqualTo("Photosynthesis")
        assertThat(archive.resolvePath("Photosynthesis")).isEqualTo("Photosynthesis")
        assertThat(archive.read("Plants")!!.path).isEqualTo("Photosynthesis")
    }

    @Test fun missingPathsReturnNull() {
        assertThat(archive.resolvePath("Nope")).isNull()
        assertThat(archive.read("Nope")).isNull()
        assertThat(archive.read("")).isNull()
    }

    @Test fun suggestsTitles() {
        assertThat(archive.suggest("photo", 5).map { it.path }).contains("Photosynthesis")
        assertThat(archive.suggest("caf", 5).map { it.path }).containsExactly("Café_au_lait")
        assertThat(archive.suggest("C++", 5).map { it.path }).contains("C++")
        assertThat(archive.suggest("  ", 5)).isEmpty()
    }

    @Test fun fullTextFindsArticleBodies() {
        val hits = archive.search("chlorophyll", 5)
        assertThat(hits.map { it.path }).containsExactly("Photosynthesis")
        assertThat(hits.single().title).isEqualTo("Photosynthesis")
        assertThat(hits.single().snippet).isNotEmpty()
        assertThat(archive.search("oxygen", 5).map { it.path }).containsExactly("Photosynthesis")
        assertThat(archive.search("", 5)).isEmpty()
    }

    @Test fun fullTextHitsDescribeTheirOwnEntry() {
        // "path" matches two articles. If the iterator's getters were read after next(), each hit's
        // title would belong to the following article (or the last read would fail).
        val hits = archive.search("path", 5)
        assertThat(hits.map { it.path }).containsExactly("What_is_this?", "Café_au_lait")
        for (hit in hits) {
            assertThat(html(hit.path)).contains("<title>${hit.title}</title>")
        }
    }

    @Test fun searchSurvivesQueryParserSyntax() {
        for (query in listOf("\"unbalanced", "((", "AND OR NOT", "C++", "*", "title:", "\u0000")) {
            archive.search(query, 5)
            archive.suggest(query, 5)
        }
        assertThat(archive.search("C++", 5).map { it.path }).contains("C++")
    }

    @Test fun callsAfterCloseReturnEmpty() {
        archive.close()
        assertThat(archive.read("index")).isNull()
        assertThat(archive.resolvePath("index")).isNull()
        assertThat(archive.search("oxygen", 5)).isEmpty()
        assertThat(archive.suggest("photo", 5)).isEmpty()
        archive.close() // closing twice is harmless
    }

    @Test fun concurrentUseAndCloseDoNotCrash() {
        val pool = Executors.newFixedThreadPool(8)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        repeat(8) { worker ->
            pool.execute {
                repeat(200) { i ->
                    try {
                        when ((worker + i) % 3) {
                            0 -> archive.read("Photosynthesis")
                            1 -> archive.search("chlorophyll", 5)
                            else -> archive.suggest("pho", 5)
                        }
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        Thread.sleep(50)
        archive.close() // mid-flight, like a pack being removed while the reader still loads images
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failures.get()).isEqualTo(0)
    }
}
