package app.granger.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.granger.core.zim.ZimArchive
import app.granger.shell.RailCovers
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.jsoup.Jsoup

/**
 * A card's picture for a Kiwix article: the article's lead image, read out of the ZIM once and kept as a small JPEG
 * under the app's cache (the OS may clear it; the card then shows its icon). Blocking; call off the main thread.
 */
class ArticleThumbnails(private val dir: File) {
    /** The cached file's path, or null when the article has no usable image. */
    fun thumbnail(archive: ZimArchive, articlePath: String, html: String): String? = try {
        val imagePath = candidates(html, articlePath).firstNotNullOfOrNull { archive.resolvePath(it) } ?: return null
        val file = File(dir, "${sha1("${archive.uuid}/$imagePath")}.jpg")
        if (file.isFile && file.length() > 0L) return file.path
        val bytes = archive.read(imagePath)?.bytes?.takeIf { it.isNotEmpty() } ?: return null
        val bitmap = decode(bytes) ?: return null
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (tmp.renameTo(file)) file.path else null
    } catch (_: Throwable) { // Includes OutOfMemoryError: a bad image never takes Home down.
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_SIDE || bounds.outHeight < MIN_SIDE || bounds.outWidth > RailCovers.MAX_SIDE || bounds.outHeight > RailCovers.MAX_SIDE) return null
        val options = BitmapFactory.Options().apply { inSampleSize = RailCovers.sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** The shorter side of a kept thumbnail, about three card widths on a phone. */
        const val TARGET_PX = 400
        private const val MIN_SIDE = 64
        private val SKIPPED_EXTENSIONS = setOf("svg", "ico")

        /**
         * The ZIM paths that may hold the article's lead image, best first: the first `img` that isn't an icon, a formula
         * or a data URI, resolved against the article's path (`../I/x.jpg` from `A/Cat`, `I/x.jpg` from `Cat`), with
         * the bare `I/<name>` form as a fallback for either layout.
         */
        fun candidates(html: String, articlePath: String): List<String> {
            val doc = Jsoup.parse(html)
            val out = LinkedHashSet<String>()
            for (img in doc.select("img[src]")) {
                val src = img.attr("src").trim()
                if (src.isEmpty() || src.startsWith("data:")) continue
                val ext = src.substringAfterLast('.', "").substringBefore('?').lowercase()
                if (ext in SKIPPED_EXTENSIONS) continue
                val cls = img.className().lowercase()
                if ("icon" in cls || "mwe-math" in cls || "noviewer" in cls) continue
                val w = img.attr("width").toIntOrNull()
                val h = img.attr("height").toIntOrNull()
                if ((w != null && w < MIN_SIDE) || (h != null && h < MIN_SIDE)) continue
                out += resolve(src, articlePath)
                out += "I/" + src.substringAfterLast('/')
                if (out.size >= 6) break
            }
            return out.toList()
        }

        private fun resolve(src: String, articlePath: String): String {
            val base = runCatching { URI("/" + articlePath.replace(" ", "%20")) }.getOrNull()
            val resolved = runCatching { base?.resolve(src.replace(" ", "%20")) }.getOrNull()
            val path = resolved?.path?.removePrefix("/") ?: src.removePrefix("./")
            return path.replace("%20", " ")
        }
    }
}
