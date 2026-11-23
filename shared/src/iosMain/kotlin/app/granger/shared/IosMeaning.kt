package app.granger.shared

import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.SidecarKind
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
import app.granger.core.library.ListedVectorSources
import app.granger.core.library.ListedVectors
import app.granger.core.library.Pack
import app.granger.core.library.VectorSources
import app.granger.core.research.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSRecursiveLock

// ---- Search by meaning (Plan I7 call 6): EmbeddingGemma 2 on LiteRT-LM is Swift's (the framework's C API); the rules,
// the vector files and the dense search are here, as Android's GrangerApp wires them.

/** Loads EmbeddingGemma in LiteRT-LM's embedding engine; Swift's `LiteRtEmbedder` behind it. */
interface EmbedderNatives {
    /** The model at [modelPath], its compiled weights kept in [cacheDir] (which exists); null when it won't load. */
    fun load(modelPath: String, cacheDir: String): EmbedderHandle?
}

/** One loaded embedder: a vector as float32s (4 bytes each, native order), null when it fails. */
interface EmbedderHandle {
    /** A search query, in EmbeddingGemma's query form. */
    fun embedQuery(text: String): NSData?

    /** A passage with its title, in the form the catalog's vector files were made with (`tools/vectors/build.py`). */
    fun embedDocument(title: String, text: String): NSData?
    fun close()
}

/**
 * Search by meaning on the iPhone, as Android's (Plan SQ Phase 6): [on] for an iPhone with the memory Android asks for
 * (DeviceTiers' 4.5 GiB, a marketed 6 GB phone); the catalog then lists search vectors, they follow their packs, the
 * embedder follows the first of them ([app.granger.core.downloads.EmbedderDownloads]) and Best matches embed the query
 * while it stays fast ([speed]).
 */
class IosMeaning internal constructor(
    totalRamBytes: Long,
    private val records: suspend () -> List<DownloadRecord>,
    private val catalogEmbedders: () -> Set<String>,
    private val packs: suspend () -> List<Pack>,
    private val cacheDir: String,
    private val io: CoroutineDispatcher,
) {
    val on: Boolean = totalRamBytes >= MIN_RAM_BYTES

    /** Swift's loader, set once at start; without it Best matches stay keyword-only. */
    var natives: EmbedderNatives? = null

    val speed = EmbedSpeed()

    private val lock = NSRecursiveLock()
    private var loaded: Pair<String, EmbedderHandle>? = null

    /** The installed embedder's download record (the catalog's embedder, installed); null when there's none. */
    private suspend fun installedEmbedder(): DownloadRecord? {
        val ids = catalogEmbedders()
        return records().firstOrNull { it.kind == ItemKind.MODEL && it.state == DownloadState.INSTALLED && it.itemId in ids }
    }

    /**
     * Debug builds and tests: a model file to use instead of the installed embedder, and vector files (path, the pack's
     * ZIM name) listed with the installed ones, as Android's `DirectoryVectorSources` lists adb-pushed files.
     */
    var debugModelPath: String? = null
    var debugVectors: List<Pair<String, String>> = emptyList()

    /** The embedder's model file: [debugModelPath], else the installed embedder's; null when there's none. */
    private suspend fun modelPath(): String? = debugModelPath ?: installedEmbedder()?.filePath

    /** [query]'s vector, or null: search by meaning off here, too slow now, no embedder installed, or it failed. */
    suspend fun embed(query: String): FloatArray? {
        if (!on || !speed.useMeaning()) return null
        return withHandle { it.embedQuery(query) }
    }

    /** A passage's vector as the vector files hold them; null without an embedder. */
    suspend fun embedDocument(title: String, text: String): FloatArray? = withHandle { it.embedDocument(title, text) }

    /** [use] on the loaded embedder (loaded first when the model changed), on [io]; null without one or on a failure. */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private suspend fun withHandle(use: (EmbedderHandle) -> NSData?): FloatArray? {
        val path = modelPath() ?: return null.also { release() }
        val natives = natives ?: return null
        return withContext(io) {
            lock.lock()
            try {
                val handle = loaded?.takeIf { it.first == path }?.second ?: run {
                    loaded?.second?.close()
                    loaded = null
                    platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(cacheDir, true, null, null)
                    natives.load(path, cacheDir)?.also { loaded = path to it }
                } ?: return@withContext null
                use(handle)?.let(::floats)?.takeIf { it.isNotEmpty() }
            } finally {
                lock.unlock()
            }
        }
    }

    /** Lets the embedder go (it's loaded again by the next query). */
    fun release() {
        lock.lock()
        try {
            loaded?.second?.close()
            loaded = null
        } finally {
            lock.unlock()
        }
    }

    /** The installed embedder's sha256, which a vector file must have been embedded with; null if unknown. */
    private suspend fun embedderSha(): String? = installedEmbedder()?.hash

    /**
     * The installed vector files with what they belong to, as Android's `installedVectors` and `ListedVectorSources`: a
     * pack's under its ZIM name, a Granger Classic's under its book id (Plan I7 Task 7a), the newest version of each,
     * opened once, skipping one cut by another chunker or embedded by another embedder than the installed one.
     */
    val sources: VectorSources = ListedVectorSources({ listed() }, { if (debugModelPath != null) null else embedderSha() })

    private suspend fun listed(): List<ListedVectors> {
        val all = records().filter { it.state == DownloadState.INSTALLED }
        val byItem = all.groupBy { it.itemId }
        val packsByPath = packs().associateBy { it.filePath }
        return all.filter { it.kind == ItemKind.VECTORS }.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
            val parentId = SidecarKind.VECTORS.parentOf(itemId) ?: return@mapNotNull null
            val parent = byItem[parentId]?.maxByOrNull { it.createdAt } ?: return@mapNotNull null
            val file = versions.maxBy { it.createdAt }.filePath
            when (parent.kind) {
                ItemKind.PACK -> packsByPath[parent.filePath]?.name?.ifBlank { null }?.let { ListedVectors(file, SourceKind.ARTICLE, it) }
                ItemKind.BOOK -> ListedVectors(file, SourceKind.BOOK, BookIds.of(BookOrigin.GRANGER, parentId))
                else -> null
            }
        } + debugVectors.map { (path, name) -> ListedVectors(path, SourceKind.ARTICLE, name) }
    }

    /**
     * Passages as the vector files hold them, for the books you add ([app.granger.core.library.BookVectors]); null
     * without an embedder (search by meaning off here, or none installed).
     */
    suspend fun embedDocuments(docs: List<Pair<String, String>>): List<FloatArray>? {
        if (!on) return null
        return docs.map { (title, text) -> embedDocument(title, text) ?: return null }
    }

    /**
     * The embedder the books' own vectors are made with and must match: the installed one's sha256, or a fixed one for
     * [debugModelPath] (tests), so they're made and found there too; null with neither.
     */
    suspend fun bookVectorsSha(): String? = if (debugModelPath != null) DEBUG_SHA else embedderSha()

    companion object {
        /** Android's `DeviceTiers.ASK_MIN_BYTES`: 4.5 GiB, a marketed 6 GB phone. */
        const val MIN_RAM_BYTES: Long = 9L * 1024 * 1024 * 1024 / 2

        /** Whether a phone with [totalRamBytes] runs a model of [role] (Android's `DeviceTier.canRun` with Ask resting). */
        fun canRun(role: ModelRole, totalRamBytes: Long): Boolean = role == ModelRole.EMBEDDER && totalRamBytes >= MIN_RAM_BYTES

        /** The embedder sha the books' own vectors carry when made with [debugModelPath]. */
        private val DEBUG_SHA = "d".repeat(64)
    }
}

/**
 * How long search by meaning takes here (Android's `EmbedSpeed`, Plan SQ Phase 6): once the median of the last few
 * searches is over [slowMs], Best matches go keyword-only, trying meaning again every [RETRY_AFTER] searches.
 */
class EmbedSpeed(private val slowMs: Long = SLOW_MS) {
    private val recent = ArrayDeque<Long>()
    private var seenFirst = false
    private var skipped = 0
    private val lock = NSRecursiveLock()

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Whether the next search should embed its query. */
    fun useMeaning(): Boolean = locked {
        if (!slow) return@locked true
        skipped++
        if (skipped < RETRY_AFTER) return@locked false
        skipped = 0
        true
    }

    /** Records one search by meaning: [ms], its embedding and dense search together. The first (the load) isn't counted. */
    fun record(ms: Long) = locked {
        if (!seenFirst) {
            seenFirst = true
            return@locked
        }
        if (slow && ms <= slowMs) recent.clear()
        recent.addLast(ms)
        while (recent.size > WINDOW) recent.removeFirst()
    }

    val slow: Boolean get() = locked { recent.size >= MIN_SEEN && recent.sorted()[recent.size / 2] > slowMs }

    companion object {
        const val SLOW_MS = 4_000L
        const val WINDOW = 5
        const val MIN_SEEN = 3
        const val RETRY_AFTER = 20
    }
}
