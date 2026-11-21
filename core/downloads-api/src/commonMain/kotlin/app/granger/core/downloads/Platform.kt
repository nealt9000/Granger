package app.granger.core.downloads

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A plain mutual-exclusion lock (common Kotlin has no `synchronized`). */
expect class QueueLock() {
    fun <T> with(block: () -> T): T
}

/** Wall-clock milliseconds. */
expect fun nowMillis(): Long

/** The free bytes on the volume holding [path] (a folder that exists, or the nearest one above it that does). */
expect fun freeBytesAt(path: String): Long

/**
 * The few file operations the queue needs, on both phones. Paths are absolute strings; '/' is the separator on both
 * (Android and iOS are Unix underneath), so [SEP] is a constant.
 */
object Files {
    const val SEP = "/"

    fun size(path: String): Long = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.takeIf { it.isRegularFile }?.size }.getOrNull() ?: 0L
    fun isFile(path: String): Boolean = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true }.getOrDefault(false)
    fun isDirectory(path: String): Boolean = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true }.getOrDefault(false)
    fun exists(path: String): Boolean = runCatching { SystemFileSystem.exists(Path(path)) }.getOrDefault(false)
    fun delete(path: String): Boolean = runCatching { SystemFileSystem.delete(Path(path), mustExist = false); true }.getOrDefault(false)

    /** Renames [from] to [to] (same folder or volume), replacing [to]; false when it couldn't. */
    fun rename(from: String, to: String): Boolean = runCatching { SystemFileSystem.atomicMove(Path(from), Path(to)); true }.getOrDefault(false)

    /** [child] under [root]: `root/child`, with one separator. */
    fun join(root: String, child: String): String = root.trimEnd('/') + SEP + child.trimStart('/')

    /** The folder holding [path]; null at the top. */
    fun parent(path: String): String? = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}
