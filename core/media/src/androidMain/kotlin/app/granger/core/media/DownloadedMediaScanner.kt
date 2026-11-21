package app.granger.core.media

import android.os.ParcelFileDescriptor
import app.granger.core.library.MediaLibrary
import java.io.File

/**
 * Android's downloaded-media scanner: the shared [DownloadedMediaScan] (Plan I6 Task 1) over the volumes' `File`s, with
 * tags read by TagLib through a read-only file descriptor, exactly as before it was shared.
 */
class DownloadedMediaScanner(
    roots: () -> List<File>,
    media: MediaLibrary,
    tagReader: TagReader = NativeTagReader,
    tagGuard: TagReadGuard = TagReadGuard.NONE,
) : DownloadedMediaScan({ roots().map { it.path } }, media, DescriptorTagReader(tagReader), tagGuard) {
    /** Item folders whose manifest couldn't be read at the last scan (skipped, rows untouched). */
    val lastUnreadable: List<File> get() = lastUnreadablePaths.map(::File)

    /** Every item folder with a readable manifest, on every mounted volume. */
    suspend fun items(): List<Pair<File, MediaManifest>> = itemPaths().map { (dir, m) -> File(dir) to m }
}

/** Opens the file read-only, then marks the guard while [reader] reads it (see [PathTagReader]). */
class DescriptorTagReader(private val reader: TagReader) : PathTagReader {
    override fun read(path: String, file: FoundFile, guard: TagReadGuard): FileTags =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            guard.begin(file.uri)
            val tags = try {
                reader.read(pfd, file)
            } catch (e: Exception) {
                guard.end()
                throw e
            }
            guard.end()
            tags
        }
}

/** The manifest in the item folder [dir]; null when missing or unreadable. */
fun MediaManifests.read(dir: File): MediaManifest? = read(dir.path)

/** Writes [manifest] into [dir] (see [MediaManifests.write]). */
fun MediaManifests.write(dir: File, manifest: MediaManifest) = write(dir.path, manifest)
