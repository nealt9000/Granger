package app.granger.media

import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import app.granger.core.downloads.Volume
import app.granger.core.media.MediaManifest
import java.io.File
import java.io.IOException

/**
 * Maps Granger's downloaded-media folders to documents, for [MediaDocumentsProvider]. Read-only and pure: it only lists
 * and resolves.
 *
 * - Ids: "root" (Granger), "music", "audiobooks", and "<volume key>:<relative path>" for an item folder
 *   ("primary:music/archive-org/<identifier>") or a file in one ("primary:music/archive-org/<identifier>/01 - Mars.mp3").
 * - Music and Audiobooks list every item folder on every mounted volume, shown by [titleOf] (the manifest's display
 *   title); a folder [titleOf] can't name isn't an item and isn't listed. An item folder lists its files by local name.
 * - Dotfiles (the manifest, `.nomedia`, embedded covers, temp files), `*.part` and `*.tmp` files are never listed or
 *   resolved, nor anything outside `music/<source>/` and `audiobooks/<source>/`. An id resolves only when its canonical
 *   path is exactly the path it names under its volume, so `..`, absolute paths and symlinks never get out.
 */
class MediaDocuments(
    private val volumes: () -> List<Volume>,
    private val volumeKey: (File) -> String,
    private val titleOf: (File) -> String?,
) {
    data class Doc(
        val id: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val lastModified: Long?,
        val isDir: Boolean,
    )

    /** Music, Audiobooks. */
    fun root(): List<Doc> = SHELVES.map { shelfDoc(it) }

    fun children(parentId: String): List<Doc> = when (parentId) {
        ROOT -> root()
        in SHELVES -> volumes().flatMap { v -> items(v, parentId) }
        else -> {
            val (volume, dir) = resolve(parentId) ?: return emptyList()
            if (!dir.isDirectory || depth(parentId) != ITEM_DEPTH || titleOf(dir) == null) emptyList()
            else dir.listFiles().orEmpty()
                .filter { it.isFile && isVisible(it.name) && contained(volume, it) }
                .sortedBy { it.name }
                .map { fileDoc(childId(parentId, it.name), it) }
        }
    }

    fun doc(id: String): Doc? = when (id) {
        ROOT -> Doc(ROOT, "Granger", Document.MIME_TYPE_DIR, null, null, isDir = true)
        in SHELVES -> shelfDoc(id)
        else -> {
            val (_, file) = resolve(id) ?: return null
            when (depth(id)) {
                ITEM_DEPTH -> if (file.isDirectory) titleOf(file)?.let { itemDoc(id, file, it) } else null
                FILE_DEPTH -> if (file.isFile) fileDoc(id, file) else null
                else -> null
            }
        }
    }

    /** The file behind [id]: null for folders, hidden files, `*.part`, or anything outside a media root. */
    fun file(id: String): File? {
        if (id == ROOT || id in SHELVES || depth(id) != FILE_DEPTH) return null
        val (_, file) = resolve(id) ?: return null
        return file.takeIf { it.isFile }
    }

    /** True when [id] is a valid document somewhere below [parentId]. */
    fun isChild(parentId: String, id: String): Boolean {
        if (id == parentId || id == ROOT) return false
        if (doc(id) == null) return false
        return when (parentId) {
            ROOT -> true
            in SHELVES -> id !in SHELVES && relativePath(id)?.startsWith("$parentId/") == true
            else -> depth(parentId) == ITEM_DEPTH && doc(parentId) != null && id.startsWith("$parentId/")
        }
    }

    private fun shelfDoc(shelf: String) =
        Doc(shelf, if (shelf == MUSIC) "Music" else "Audiobooks", Document.MIME_TYPE_DIR, null, null, isDir = true)

    private fun items(volume: Volume, shelf: String): List<Doc> {
        val key = volumeKey(volume.root)
        return SOURCES.flatMap { source ->
            File(volume.root, "$shelf/$source").listFiles().orEmpty()
                .filter { it.isDirectory && isVisible(it.name) && contained(volume, it) }
                .sortedBy { it.name }
                .mapNotNull { dir -> titleOf(dir)?.let { itemDoc("$key:$shelf/$source/${dir.name}", dir, it) } }
        }
    }

    private fun itemDoc(id: String, dir: File, title: String) =
        Doc(id, title, Document.MIME_TYPE_DIR, null, dir.lastModified(), isDir = true)

    private fun fileDoc(id: String, file: File) =
        Doc(id, file.name, mimeTypeOf(file.name), file.length(), file.lastModified(), isDir = false)

    private fun childId(parentId: String, name: String) = "$parentId/$name"

    private fun relativePath(id: String): String? = id.substringAfter(':', "").takeIf { ':' in id }

    private fun depth(id: String): Int = relativePath(id)?.split('/')?.size ?: 0

    /** The volume and file [id] names, when every rule holds; else null. */
    private fun resolve(id: String): Pair<Volume, File>? {
        val key = id.substringBefore(':', "")
        val rel = relativePath(id) ?: return null
        if (key.isEmpty() || rel.isEmpty() || rel.startsWith("/") || '\\' in rel || '\u0000' in rel) return null
        val segments = rel.split('/')
        if (segments.size !in ITEM_DEPTH..FILE_DEPTH) return null
        if (segments[0] !in SHELVES || segments[1] !in SOURCES) return null
        if (segments.any { it.isEmpty() || it == "." || it == ".." || !isVisible(it) }) return null
        val volume = volumes().firstOrNull { volumeKey(it.root) == key } ?: return null
        val file = File(volume.root, rel)
        if (!contained(volume, file)) return null
        // A file belongs to an item: its folder must be one (have a readable manifest).
        if (segments.size == FILE_DEPTH && titleOf(file.parentFile!!) == null) return null
        return volume to file
    }

    /** [file] exists, is under its shelf on [volume], and its canonical path is the path itself: no symlink on the way. */
    private fun contained(volume: Volume, file: File): Boolean = try {
        val root = volume.root.canonicalFile
        val canonical = file.canonicalFile
        val relative = file.absoluteFile.relativeTo(volume.root.absoluteFile).path
        file.exists() && !relative.startsWith("..") && canonical == File(root, relative) &&
            SHELVES.any { canonical.path.startsWith(File(root, it).path + File.separator) }
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    companion object {
        const val ROOT = "root"
        const val MUSIC = "music"
        const val AUDIOBOOKS = "audiobooks"
        private val SHELVES = listOf(MUSIC, AUDIOBOOKS)
        private val SOURCES = listOf(MediaManifest.ARCHIVE_ORG, MediaManifest.LIBRIVOX)
        private const val ITEM_DEPTH = 3
        private const val FILE_DEPTH = 4
        const val COVER = "cover.jpg"

        /** Not a dotfile (manifest, `.nomedia`, `.cover-*`, temp files) and not a download in progress. */
        fun isVisible(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith(".") && !name.endsWith(".part") && !name.endsWith(".tmp")

        private val KNOWN = mapOf(
            "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac",
            "m4a" to "audio/mp4", "m4b" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        )

        fun mimeTypeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return KNOWN[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
