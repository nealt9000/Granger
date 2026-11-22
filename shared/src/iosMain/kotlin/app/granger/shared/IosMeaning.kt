package app.granger.shared

import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.SidecarKind
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.research.DenseFinder
import app.granger.core.research.HtmlPassages
import app.granger.core.research.Passage
import app.granger.core.research.ResearchLanguage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.text.Chunkers
import app.granger.core.vectors.VectorFile
import app.granger.core.vectors.VectorHit
import app.granger.core.vectors.VectorSearch
import app.granger.core.zim.ZimArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val archive: (String) -> ZimArchive?,
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

    /** A passage's vector as the vector files hold them; null without an embedder. For building test vectors. */
    internal suspend fun embedDocument(title: String, text: String): FloatArray? = withHandle { it.embedDocument(title, text) }

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

    private val opened = HashMap<String, VectorFile>()
    private val openedLock = NSRecursiveLock()

    /**
     * The packs' installed vector files with the pack's ZIM name, as Android's `installedVectors` and
     * `ListedVectorSources`: the newest version of each, opened once, skipping one cut by another chunker or embedded by
     * another embedder than the installed one.
     */
    internal suspend fun sources(): List<Pair<VectorFile, String>> {
        val all = records().filter { it.state == DownloadState.INSTALLED }
        val byItem = all.groupBy { it.itemId }
        val packsByPath = packs().associateBy { it.filePath }
        val listed = all.filter { it.kind == ItemKind.VECTORS }.groupBy { it.itemId }.mapNotNull { (itemId, versions) ->
            val parentId = SidecarKind.VECTORS.parentOf(itemId) ?: return@mapNotNull null
            val parent = byItem[parentId]?.maxByOrNull { it.createdAt }?.takeIf { it.kind == ItemKind.PACK } ?: return@mapNotNull null
            val name = packsByPath[parent.filePath]?.name?.ifBlank { null } ?: return@mapNotNull null
            versions.maxBy { it.createdAt }.filePath to name
        } + debugVectors
        val sha = if (debugModelPath != null) null else embedderSha()
        openedLock.lock()
        try {
            val paths = listed.map { it.first }.toSet()
            opened.keys.filter { it !in paths }.forEach { opened.remove(it)?.close() }
            return listed.mapNotNull { (path, name) ->
                val file = opened[path] ?: VectorFile.open(path)?.also { opened[path] = it } ?: return@mapNotNull null
                file.takeIf { it.header.chunkerVersion == Chunkers.VERSION && (sha == null || it.header.embedderSha256 == sha) }?.let { it to name }
            }
        } finally {
            openedLock.unlock()
        }
    }

    /** The dense half of Best matches over the packs. */
    val finder: DenseFinder = PackDenseFinder(this, packs, archive, io)

    companion object {
        /** Android's `DeviceTiers.ASK_MIN_BYTES`: 4.5 GiB, a marketed 6 GB phone. */
        const val MIN_RAM_BYTES: Long = 9L * 1024 * 1024 * 1024 / 2

        /** Whether a phone with [totalRamBytes] runs a model of [role] (Android's `DeviceTier.canRun` with Ask resting). */
        fun canRun(role: ModelRole, totalRamBytes: Long): Boolean = role == ModelRole.EMBEDDER && totalRamBytes >= MIN_RAM_BYTES
    }
}

/**
 * Finds passages by meaning over the packs' vector files: the [k] best vectors, turned back into [Passage]s by
 * re-splitting their article as the keyword side does (Android's `DensePassageFinder`, its articles half: Best matches
 * on the iPhone search packs, not books). A rebuilt passage whose text no longer hashes to the vector's is dropped.
 */
internal class PackDenseFinder(
    private val meaning: IosMeaning,
    private val packs: suspend () -> List<Pack>,
    private val archive: (String) -> ZimArchive?,
    private val io: CoroutineDispatcher,
    private val k: Int = 24,
    private val probes: Int = PROBES,
) : DenseFinder {
    override suspend fun find(query: FloatArray): List<Passage> = withContext(io) {
        val byName = packs().filter { it.status == PackStatus.OK && it.name.isNotBlank() }
            .groupBy { it.name }.mapValues { (_, same) -> same.maxBy { it.date } }
        val usable = meaning.sources().filter { it.second in byName }
        if (usable.isEmpty()) return@withContext emptyList()
        val dims = usable.groupBy { it.first.header.dims }.maxBy { it.value.size }.key
        val searched = usable.filter { it.first.header.dims == dims }
        val nameOf = searched.associate { it.first to it.second }
        val hits = VectorSearch.top(query, searched.map { it.first }, k, io, probes = probes)
        val gate = Semaphore(PARALLEL_REBUILDS)
        val rebuilt = coroutineScope {
            hits.groupBy { nameOf.getValue(it.file) to it.file.key(it.row).key }.map { (where, same) ->
                async {
                    gate.withPermit {
                        try {
                            val pack = byName.getValue(where.first)
                            archive(pack.id)?.let { articles(pack, it, where.second, same) }.orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten().toMap()
        }
        hits.mapNotNull { rebuilt[it] }
    }

    private fun articles(pack: Pack, archive: ZimArchive, path: String, hits: List<VectorHit>): List<Pair<VectorHit, Passage>> {
        val item = archive.read(path) ?: return emptyList()
        if (!item.mimeType.startsWith("text/html") && !item.mimeType.startsWith("application/xhtml")) return emptyList()
        val html = item.bytes.decodeToString()
        val parts = HtmlPassages.split(html)
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { app.granger.librivox.decodeEntities(it).trim() }.orEmpty()
        val location = archive.resolvePath(path) ?: path
        return hits.mapNotNull { hit ->
            val part = parts.getOrNull(hit.file.key(hit.row).ordinal)
            if (part == null || Chunkers.hash(part.text) != hit.file.hash(hit.row)) return@mapNotNull null
            hit to Passage(
                kind = SourceKind.ARTICLE,
                sourceId = pack.id,
                sourceTitle = title.ifEmpty { part.heading },
                packTitle = pack.title,
                location = location,
                anchor = part.anchor,
                heading = part.heading,
                text = part.text,
                meta = SourceMeta(date = pack.date),
                cosine = hit.score.toDouble(),
                language = ResearchLanguage.ofCode(pack.language),
            )
        }
    }

    companion object {
        /** Android's `DensePassageFinder.PROBES`: lists read from version 2 vector files. */
        const val PROBES = 1024
        const val PARALLEL_REBUILDS = 4
        private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
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
