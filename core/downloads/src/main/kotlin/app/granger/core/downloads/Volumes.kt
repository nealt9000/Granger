package app.granger.core.downloads

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A storage volume Granger can write to. [root] is the app's external-files folder on it. */
data class Volume(
    val root: File,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    /** Bytes in Granger's `packs` and `models` folders on this volume. */
    val grangerBytes: Long,
) {
    val packsDir: File get() = File(root, "packs")
    val modelsDir: File get() = File(root, "models")
}

fun interface VolumeSource {
    /** Mounted volumes, internal storage first. Blocking: touches the disk. */
    fun list(): List<Volume>
}

/** Internal storage plus any SD card, via `getExternalFilesDirs` (no storage permission needed). */
class AndroidVolumes(private val context: Context) : VolumeSource {
    override fun list(): List<Volume> {
        val storage = context.getSystemService(StorageManager::class.java)
        // A volume the framework cannot describe is skipped rather than failing the whole list.
        return context.getExternalFilesDirs(null).filterNotNull()
            .mapNotNull { root -> runCatching { describe(storage, root) }.getOrNull() }
    }

    private fun describe(storage: StorageManager, root: File): Volume? {
        if (Environment.getExternalStorageState(root) != Environment.MEDIA_MOUNTED) return null
        val removable = Environment.isExternalStorageRemovable(root)
        return Volume(
            root = root,
            label = storage.getStorageVolume(root)?.getDescription(context)
                ?: if (removable) "SD card" else "Internal storage",
            removable = removable,
            freeBytes = root.usableSpace,
            totalBytes = root.totalSpace,
            grangerBytes = sizeOf(File(root, "packs")) + sizeOf(File(root, "models")),
        )
    }

    private fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
}
