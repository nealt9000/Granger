package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val url = "https://example.test/catalog.json"
    private val http = FakeHttp()
    private val dispatcher = StandardTestDispatcher()
    private val cacheDir by lazy { File(tmp.root, "catalog") }

    private fun repo(includeUnevaluated: Boolean = true) =
        CatalogRepository(http.client(), url, TestKeys.PUBLIC, cacheDir, includeUnevaluated, dispatcher, now = { 42L })

    private fun serve(bytes: ByteArray, sig: ByteArray = TestKeys.sign(bytes)) {
        http.routes[url] = bytes
        http.routes["$url.sig"] = sig
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test fun refreshVerifiesCachesAndPublishes() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        val state = repo.state.value
        assertThat(state.catalog!!.packs.single().title).isEqualTo("WikiMed")
        assertThat(state.fetchedAt).isEqualTo(42L)
        assertThat(state.error).isNull()
        assertThat(state.refreshing).isFalse()
        assertThat(File(cacheDir, "catalog.json").readBytes()).isEqualTo(catalogJson())
        assertThat(File(cacheDir, "catalog.json.sig").exists()).isTrue()
    }

    @Test fun loadUsesTheCacheWithoutTheNetwork() = test {
        serve(catalogJson())
        repo().refresh()
        http.requested.clear()
        http.offline = true

        val fresh = repo()
        fresh.load()
        assertThat(fresh.state.value.catalog!!.packs.single().id).isEqualTo("med")
        assertThat(http.requested).isEmpty()
    }

    @Test fun badSignatureKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        val goodBytes = File(cacheDir, "catalog.json").readBytes()

        serve(catalogJson(packTitle = "Evil"), sig = TestKeys.sign(catalogJson()))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("WikiMed")
        assertThat(repo.state.value.error).isEqualTo(CatalogError.BAD_SIGNATURE)
        assertThat(File(cacheDir, "catalog.json").readBytes()).isEqualTo(goodBytes)
    }

    @Test fun offlineOrMissingSignatureKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()

        http.offline = true
        repo.refresh()
        assertThat(repo.state.value.catalog).isNotNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
        assertThat(repo.state.value.refreshing).isFalse()

        http.offline = false
        http.routes.remove("$url.sig")
        repo.refresh()
        assertThat(repo.state.value.catalog).isNotNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
    }

    @Test fun firstRunOfflineHasNoCatalog() = test {
        http.offline = true
        val repo = repo()
        repo.refresh()
        assertThat(repo.state.value.catalog).isNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
    }

    @Test fun anOlderSignedCatalogIsNotAcceptedOverANewerOne() = test {
        serve(catalogJson(generatedAt = "2026-10-16T00:00:00Z", packTitle = "New"))
        val repo = repo()
        repo.refresh()

        serve(catalogJson(generatedAt = "2026-09-01T00:00:00Z", packTitle = "Old"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("New")
        assertThat(repo.state.value.error).isEqualTo(CatalogError.OLDER_THAN_CACHED)

        // Also across restarts: the cache is the reference.
        val restarted = repo()
        restarted.refresh()
        assertThat(restarted.state.value.catalog!!.packs.single().title).isEqualTo("New")
    }

    @Test fun aNewerCatalogReplacesTheCache() = test {
        serve(catalogJson(generatedAt = "2026-10-16T00:00:00Z", packTitle = "One"))
        val repo = repo()
        repo.refresh()
        serve(catalogJson(generatedAt = "2026-10-17T00:00:00Z", packTitle = "Two"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("Two")

        val restarted = repo()
        restarted.load()
        assertThat(restarted.state.value.catalog!!.packs.single().title).isEqualTo("Two")
    }

    @Test fun aTamperedCacheIsIgnored() = test {
        cacheDir.mkdirs()
        File(cacheDir, "catalog.json").writeBytes(catalogJson(packTitle = "Edited"))
        File(cacheDir, "catalog.json.sig").writeBytes(TestKeys.sign(catalogJson()))
        val repo = repo()
        repo.load()
        assertThat(repo.state.value.catalog).isNull()
    }

    @Test fun futureFormatKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        serve(catalogJson(formatVersion = 2, generatedAt = "2026-12-01T00:00:00Z"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.formatVersion).isEqualTo(1)
        assertThat(repo.state.value.error).isEqualTo(CatalogError.UNSUPPORTED_FORMAT)
    }

    @Test fun releaseBuildsHideUnevaluatedModels() = test {
        serve(catalogJson())
        val repo = repo(includeUnevaluated = false)
        repo.refresh()
        assertThat(repo.state.value.catalog!!.models.map { it.id }).containsExactly("e2b")
    }
}
