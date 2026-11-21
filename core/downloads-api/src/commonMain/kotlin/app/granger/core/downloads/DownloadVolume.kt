package app.granger.core.downloads

/**
 * A storage volume downloads can go to, as the shared screens see it (Plan I4b Task 6): [root] is the folder
 * [Downloads.enqueue] takes. Android's `Volume` (core:downloads, a `File` root) maps onto it; the iPhone has one, its
 * Documents folder.
 */
data class DownloadVolume(
    val root: String,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    /** Bytes in Granger's own folders on this volume. */
    val grangerBytes: Long,
)

fun interface DownloadVolumes {
    /** Mounted volumes, internal storage first. Blocking: touches the disk. */
    fun list(): List<DownloadVolume>
}
