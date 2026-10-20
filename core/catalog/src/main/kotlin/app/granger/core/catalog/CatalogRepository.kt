package app.granger.core.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant

data class CatalogState(
    /** The catalog to show, already filtered for this build; null until one has been verified. */
    val catalog: Catalog? = null,
    /** When [catalog] was downloaded (epoch ms). */
    val fetchedAt: Long? = null,
    val refreshing: Boolean = false,
    /** Why the last refresh kept the old catalog; null after a successful refresh. */
    val error: CatalogError? = null,
)

interface CatalogSource {
    val state: StateFlow<CatalogState>

    /** Shows the last good catalog from the cache. No network. */
    suspend fun load()

    /** Downloads, verifies and caches the catalog. On any failure the shown catalog stays and [CatalogState.error] says why. */
    suspend fun refresh()
}

class CatalogRepository(
    private val client: OkHttpClient,
    private val url: String,
    private val publicKey: ByteArray,
    private val cacheDir: File,
    private val includeUnevaluatedModels: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : CatalogSource {
    private val _state = MutableStateFlow(CatalogState())
    override val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val jsonFile = File(cacheDir, "catalog.json")
    private val sigFile = File(cacheDir, "catalog.json.sig")

    /** The verified catalog before [Catalog.forBuild]; the reference for rejecting older ones. */
    private var current: Catalog? = null

    override suspend fun load() = mutex.withLock { loadCacheOnce() }

    override suspend fun refresh() = mutex.withLock {
        loadCacheOnce()
        _state.update { it.copy(refreshing = true) }
        var error: CatalogError? = CatalogError.NETWORK
        try {
            error = withContext(io) { fetchAndCache() }
        } finally {
            _state.update { it.copy(refreshing = false, error = error) }
        }
    }

    private suspend fun loadCacheOnce() {
        if (current != null) return
        withContext(io) {
            if (!jsonFile.isFile || !sigFile.isFile) return@withContext
            val parsed = CatalogParser.parse(jsonFile.readBytes(), sigFile.readBytes(), publicKey)
            if (parsed is ParseResult.Ok) show(parsed.catalog, jsonFile.lastModified())
        }
    }

    /** Null on success; otherwise why the shown catalog was kept. */
    private fun fetchAndCache(): CatalogError? {
        val bytes = get(url) ?: return CatalogError.NETWORK
        val signature = get("$url.sig") ?: return CatalogError.NETWORK
        val catalog = when (val parsed = CatalogParser.parse(bytes, signature, publicKey)) {
            is ParseResult.Invalid -> return parsed.error
            is ParseResult.Ok -> parsed.catalog
        }
        // A replayed old catalog is validly signed; never step backwards (it could re-list a pulled pack).
        val shown = current
        if (shown != null && Instant.parse(catalog.generatedAt) < Instant.parse(shown.generatedAt)) {
            return CatalogError.OLDER_THAN_CACHED
        }
        writeCache(bytes, signature)
        show(catalog, now())
        return null
    }

    private fun show(catalog: Catalog, fetchedAt: Long) {
        current = catalog
        _state.update { it.copy(catalog = catalog.forBuild(includeUnevaluatedModels), fetchedAt = fetchedAt) }
    }

    private fun get(target: String): ByteArray? = try {
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) null
            else body.bytes().takeIf { it.size <= MAX_BYTES }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(bytes: ByteArray, signature: ByteArray) {
        val tmpJson = File(cacheDir, "catalog.json.tmp")
        val tmpSig = File(cacheDir, "catalog.json.sig.tmp")
        try {
            cacheDir.mkdirs()
            tmpJson.writeBytes(bytes)
            tmpSig.writeBytes(signature)
            // A crash between the two renames leaves a mismatched pair, which fails verification and is
            // treated as no cache: safe, and fixed by the next refresh.
            tmpJson.renameTo(jsonFile)
            tmpSig.renameTo(sigFile)
        } catch (e: IOException) {
            // The catalog is verified, so it is still shown; only the cache is not updated.
            tmpJson.delete()
            tmpSig.delete()
        }
    }

    private companion object {
        // The English Kiwix catalog is about 1 MB; every language would be about 3 MB.
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
