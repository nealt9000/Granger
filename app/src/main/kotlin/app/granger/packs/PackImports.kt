package app.granger.packs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.granger.R
import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Pack
import app.granger.formatBytes
import app.granger.sources.DownloadPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What adding a pack from a file did (Plan E1 item 4). */
sealed interface PackImportResult {
    data class Queued(val title: String) : PackImportResult
    data class NotAZim(val name: String) : PackImportResult
    data class AlreadyHave(val title: String) : PackImportResult
    data class NoSpace(val bytes: Long) : PackImportResult
    data object NoStorage : PackImportResult
    data object Unreadable : PackImportResult

    fun text(): UiText = when (this) {
        is Queued -> UiText.Res(R.string.packs_import_queued, title)
        is NotAZim -> UiText.Res(R.string.packs_import_not_zim, name)
        is AlreadyHave -> UiText.Res(R.string.packs_import_have, title)
        is NoSpace -> UiText.Res(R.string.packs_import_no_space, formatBytes(bytes))
        NoStorage -> UiText.Res(R.string.packs_import_no_storage)
        Unreadable -> UiText.Res(R.string.packs_import_unreadable)
    }
}

/**
 * Adds a picked `.zim` (Plan E1 item 4): reads its name, size and header, keeps read access so a copy that stops can
 * resume after a restart, and queues the copy on the download volume. [read] and [keep] are the Android parts.
 */
class PackImports(
    private val read: suspend (Uri) -> PackImport.Picked?,
    private val keep: (Uri) -> Unit,
    private val catalog: () -> Catalog?,
    private val packs: suspend () -> List<Pack>,
    private val downloads: Downloads,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun add(uri: Uri): PackImportResult {
        val picked = read(uri) ?: return PackImportResult.Unreadable
        val uuid = PackImport.uuidOf(picked.header) ?: return PackImportResult.NotAZim(picked.name)
        val spec = PackImport.spec(picked, uuid, catalog())
        packs().firstOrNull { it.id.equals(uuid, ignoreCase = true) }?.let { return PackImportResult.AlreadyHave(it.title) }
        val root = withContext(io) {
            val list = volumes.list()
            list.firstOrNull { it.root.path == prefs.volumePath }?.root ?: list.firstOrNull()?.root
        } ?: return PackImportResult.NoStorage
        keep(uri)
        // No network is used, so Wi-Fi only doesn't apply.
        return when (val r = downloads.enqueue(spec, root, wifiOnly = false)) {
            EnqueueResult.Queued, EnqueueResult.AlreadyActive -> PackImportResult.Queued(spec.title)
            EnqueueResult.AlreadyInstalled -> PackImportResult.AlreadyHave(spec.title)
            is EnqueueResult.NoSpace -> PackImportResult.NoSpace(r.neededBytes)
        }
    }

    companion object {
        /** The Android parts: the picked file's name, size and first bytes, and a lasting read grant. */
        fun forContext(context: Context, catalog: () -> Catalog?, packs: suspend () -> List<Pack>, downloads: Downloads, volumes: VolumeSource, prefs: DownloadPrefs) =
            PackImports(
                read = { uri -> withContext(Dispatchers.IO) { runCatching { readPicked(context, uri) }.getOrNull() } },
                keep = { uri ->
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                },
                catalog = catalog, packs = packs, downloads = downloads, volumes = volumes, prefs = prefs,
            )

        private fun readPicked(context: Context, uri: Uri): PackImport.Picked? {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            if (size <= 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (size <= 0) return null
            val header = ByteArray(PackImport.HEADER_BYTES)
            val n = resolver.openInputStream(uri)?.use { input ->
                var read = 0
                while (read < header.size) { val k = input.read(header, read, header.size - read); if (k < 0) break; read += k }
                read
            } ?: return null
            return PackImport.Picked(uri.toString(), name, size, if (n == header.size) header else header.copyOf(n))
        }
    }
}
