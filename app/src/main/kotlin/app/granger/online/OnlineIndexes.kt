package app.granger.online

import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.catalog.Catalog
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.SidecarKind
import app.granger.core.downloads.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Wikipedia online's small index set (spec §6 Phase 1, Plan O1 Task 8): each knowledge language's Top QID index and
 * time index (17 + 2.3 MB English, 16 + 2.2 MB Spanish), so Explore, topic pages, the Timeline and the games work with
 * nothing else downloaded. They're sidecars of the online source (`wikipedia_en_online.gqid`), not of a pack:
 * - queued on the first start with Wikipedia online on and the gate open, from the catalog's Top entry;
 * - not queued when a pack already holds the same file (builder's call 7: one copy), and removed once one does;
 * - removed when their language is no longer a knowledge language;
 * - a newer catalog snapshot replaces them, the older removed once the new one is in.
 * Big downloads on Wi-Fi only holds them for Wi-Fi like any download; [startNow] is Explore's "Get it now".
 */
class OnlineIndexes(
    private val downloads: Downloads,
    private val volumeRoot: () -> File?,
    private val wifiOnly: () -> Boolean,
    /** The set's name in Downloads, in the app's language. */
    private val title: () -> String = { "" },
) {
    private val mutex = Mutex()
    /** Set by [startNow]: the set no longer waits for Wi-Fi. */
    @Volatile private var now = false

    /**
     * [packFiles] are the files of the packs the library lists (all of them, except under the debug preview that hides
     * the Wikipedias): only their sidecars stand in for the online set.
     */
    suspend fun reconcile(
        catalog: Catalog?, records: List<DownloadRecord>, languages: List<String>, enabled: Boolean, online: Boolean,
        packFiles: Set<String>? = null,
    ) = mutex.withLock {
        val byItem = records.groupBy { it.itemId }
        val wanted = if (enabled) languages.toSet() else emptySet()
        // The index set of a language that's gone.
        for (r in records) {
            val source = SidecarKind.parentOf(r.itemId)?.takeIf(OnlineSources::isOnline) ?: continue
            if (OnlineWikipediaArchive.languageOf(source) !in wanted) downloads.remove(r.itemId)
        }
        if (catalog == null) return@withLock
        val installedHashes = records.filter { r ->
            val parent = SidecarKind.parentOf(r.itemId)
            r.state == DownloadState.INSTALLED && parent != null && !OnlineSources.isOnline(parent) &&
                (packFiles == null || byItem[parent].orEmpty().any { it.filePath in packFiles })
        }.mapTo(HashSet()) { it.hash }
        for (lang in wanted) {
            val item = catalog.packs.firstOrNull { it.id == topOf(lang) } ?: continue
            val source = OnlineWikipediaArchive.packName(lang)
            for (kind in KINDS) {
                val file = kind.fileOf(item) ?: continue
                val itemId = kind.itemIdOf(source)
                val versions = byItem[itemId].orEmpty()
                if (file.sha256 in installedHashes) {
                    // A pack carries the same file: its copy serves Wikipedia online too.
                    for (v in versions.filter { !it.isActive }) downloads.removeVersion(v.itemId, v.hash)
                    continue
                }
                if (versions.any { it.hash == file.sha256 && it.state == DownloadState.INSTALLED }) {
                    for (old in versions.filter { it.hash != file.sha256 && !it.isActive }) downloads.removeVersion(old.itemId, old.hash)
                    continue
                }
                if (!online || versions.any { it.hash == file.sha256 || it.isActive }) continue
                val root = volumeRoot() ?: continue
                val spec = kind.specOf(item, file)
                downloads.enqueue(spec.copy(itemId = itemId, title = title(), relativePath = spec.relativePath.replace(item.id, source)), root, wifiOnly() && !now)
            }
        }
    }

    /**
     * Explore's "Get it now" on mobile data: the waiting set is queued again without waiting for Wi-Fi (the records'
     * change brings [reconcile] round, which queues it so).
     */
    suspend fun startNow(records: List<DownloadRecord>) {
        now = true
        for (r in pending(records)) if (r.state != DownloadState.RUNNING) downloads.cancel(r.itemId)
    }

    companion object {
        val KINDS = listOf(SidecarKind.QID_INDEX, SidecarKind.TIME_INDEX)

        /** The catalog entry whose index set Wikipedia online takes: Top, in its smallest flavour. */
        fun topOf(lang: String) = "wikipedia_${lang}_top_mini"

        /** What Explore says while the set is coming: nothing, on its way, or waiting for Wi-Fi. */
        fun waitOf(records: List<DownloadRecord>, unmetered: Boolean): IndexWait {
            val p = pending(records).filter { it.state != DownloadState.FAILED }
            return when {
                p.isEmpty() -> IndexWait.NONE
                !unmetered && p.any { it.wifiOnly } && p.none { it.state == DownloadState.RUNNING } -> IndexWait.WAITING_FOR_WIFI
                else -> IndexWait.COMING
            }
        }

        /** Records of the online index set still to come (queued, running or waiting for Wi-Fi). */
        fun pending(records: List<DownloadRecord>): List<DownloadRecord> =
            records.filter { r -> r.isActive && SidecarKind.parentOf(r.itemId)?.let(OnlineSources::isOnline) == true }
    }
}
