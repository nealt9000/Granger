package app.granger.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Cover art as `content://app.granger.artwork/<token>`, for readers that can't open Granger's `file://` paths
 * (Android Auto, the launcher's widget host, the session's controllers). [ArtworkProvider] serves them.
 *
 * The token is URL-safe Base64 (no padding) of `<root index>/<path relative to that root>`. A token resolves only to an
 * existing image file (`.jpg`, `.jpeg`, `.png`, `.webp`) whose canonical path is the path itself under its root: no
 * `..`, no symlink on the way. [roots] is `filesDir/media-covers` first, then every volume's `music/` and `audiobooks/`
 * (see [forContext]); it's read on every call.
 */
class Artwork(private val roots: () -> List<File>) {

    /** content://app.granger.artwork/<token> for a cover under a root, else null. */
    fun uri(coverPath: String): Uri? {
        val file = File(coverPath)
        if (!isImage(file.name)) return null
        roots().forEachIndexed { index, root ->
            val relative = relativeTo(root, file) ?: return@forEachIndexed
            val token = ENCODER.encodeToString("$index/$relative".toByteArray(Charsets.UTF_8))
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build()
        }
        return null
    }

    /** The file a token names, if it's an image under a root (canonical path checked), else null. */
    fun file(token: String): File? {
        val decoded = try {
            String(DECODER.decode(token), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val slash = decoded.indexOf('/')
        if (slash <= 0) return null
        val index = decoded.substring(0, slash).takeIf { s -> s.all { it in '0'..'9' } }?.toIntOrNull() ?: return null
        val relative = decoded.substring(slash + 1)
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.contains('\u0000') }) return null
        if (!isImage(segments.last())) return null
        val root = roots().getOrNull(index) ?: return null
        val file = File(root, relative)
        return file.takeIf { relativeTo(root, it) == relative }
    }

    /** [file]'s path under [root] if it's an existing file there and its canonical path is the path itself, else null. */
    private fun relativeTo(root: File, file: File): String? = try {
        val canonicalRoot = root.canonicalFile
        val canonical = file.canonicalFile
        val relative = file.absoluteFile.relativeTo(root.absoluteFile).path
        val inside = relative.isNotEmpty() && !relative.startsWith("..") && !File(relative).isAbsolute
        if (inside && file.isFile && canonical == File(canonicalRoot, relative)) relative.replace(File.separatorChar, '/') else null
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null                                         // relativeTo: no common root
    } catch (e: SecurityException) {
        null
    }

    companion object {
        const val AUTHORITY = "app.granger.artwork"

        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
        private val TYPES = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp")

        private fun isImage(name: String) = mimeType(name) != null

        /** The image MIME type for a cover's file name, or null for anything that isn't one. */
        fun mimeType(name: String): String? =
            name.substringAfterLast('.', "").lowercase().takeIf { name.contains('.') }?.let(TYPES::get)

        /**
         * The roots on this phone: `filesDir/media-covers` (phone scans), then each volume's `music/` and `audiobooks/`
         * (downloaded items keep their covers there). A volume that isn't there keeps its place, under a path nothing
         * can exist under, so the other volumes' tokens don't move. The volume list is reused for a few seconds: a
         * queue of hundreds of songs asks for every item's cover at once, on the main thread.
         */
        fun forContext(context: Context): Artwork {
            val app = context.applicationContext ?: context
            var cached: Pair<Long, List<File>>? = null
            val lock = Any()
            return Artwork {
                synchronized(lock) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    cached?.takeIf { now - it.first < ROOTS_TTL_MS }?.second ?: run {
                        val volumes = runCatching { app.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
                        val roots = listOf(File(app.filesDir, "media-covers")) + volumes.flatMapIndexed { i, dir ->
                            val root = dir ?: File("/dev/null/granger-volume-$i")
                            listOf(File(root, "music"), File(root, "audiobooks"))
                        }
                        cached = now to roots
                        roots
                    }
                }
            }
        }

        private const val ROOTS_TTL_MS = 5_000L
    }
}
