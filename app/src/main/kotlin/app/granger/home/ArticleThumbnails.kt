package app.granger.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.granger.core.zim.ZimArchive
import app.granger.shell.RailCovers
import java.io.File
import java.security.MessageDigest

/**
 * A card's picture for a Kiwix article: the article's lead image, read out of the ZIM once and kept as a small JPEG
 * under the app's cache (the OS may clear it; the card then shows its icon). Blocking; call off the main thread.
 */
class ArticleThumbnails(private val dir: File) {
    /** The cached file's path, or null when the article has no usable image; [targetPx] sizes it (a topic page's header is larger). */
    fun thumbnail(archive: ZimArchive, articlePath: String, html: String, targetPx: Int = TARGET_PX): String? = try {
        // The first that the pack holds and that is big enough as stored: a pack can keep a drawing far smaller than the
        // page draws it, and the next picture then stands (as the picture rule was graded, Plan EX P0).
        PlateRule.ranked(html, articlePath).asSequence().mapNotNull { (path, whole) -> archive.resolvePath(path)?.let { it to whole } }.distinctBy { it.first }.take(MAX_TRIED)
            .firstNotNullOfOrNull { (path, whole) -> cached(archive, path, targetPx, whole) }
    } catch (_: Throwable) { // Includes OutOfMemoryError: a bad image never takes Home down.
        null
    }

    /**
     * One picture of the archive by its path (a broad topic's figure, Plan EX), kept the same way; null when it's absent.
     * One that isn't a JPEG is a drawing (a logo, a diagram, a map), to be shown whole ([isWhole]).
     */
    fun picture(archive: ZimArchive, imagePath: String, targetPx: Int = TARGET_PX): String? = try {
        val drawing = imagePath.lowercase().removeSuffix(".webp").substringAfterLast('.', "") !in setOf("jpg", "jpeg")
        archive.resolvePath(imagePath)?.let { cached(archive, it, targetPx, drawing) }
    } catch (_: Throwable) {
        null
    }

    private fun cached(archive: ZimArchive, imagePath: String, targetPx: Int, whole: Boolean = false): String? {
        val size = if (targetPx == TARGET_PX) "" else "@$targetPx"
        // "#2": thumbnails made before transparent pictures were flattened onto white (they came out black) are remade.
        // A drawing or an emblem says so in its name ([isWhole]): whoever shows it knows not to crop it.
        val file = File(dir, "${sha1("${archive.uuid}/$imagePath$size#2")}${if (whole) WHOLE_SUFFIX else ".jpg"}")
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())    // last shown: the pruning keeps the ones in use
            return file.path
        }
        val bytes = archive.read(imagePath)?.bytes?.takeIf { it.isNotEmpty() } ?: return null
        val bitmap = decode(bytes, targetPx)?.let(::opaque) ?: return null
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return if (tmp.renameTo(file)) file.path else null
    }

    /**
     * Keeps the cache under [maxBytes] by deleting the thumbnails shown longest ago (clean-up list: it was never pruned).
     * A deleted one is made again when its card next shows. Blocking; call off the main thread.
     */
    fun prune(maxBytes: Long = MAX_CACHE_BYTES) {
        val files = dir.listFiles { f -> f.isFile }?.toList() ?: return
        val staleTmp = System.currentTimeMillis() - 60_000L    // a newer .tmp may still be being written
        files.filter { it.name.endsWith(".tmp") && it.lastModified() < staleTmp }.forEach { it.delete() }
        val kept = files.filterNot { it.name.endsWith(".tmp") }.sortedByDescending { it.lastModified() }
        var total = 0L
        for (f in kept) {
            total += f.length()
            if (total > maxBytes) f.delete()
        }
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < MIN_SIDE || bounds.outHeight < MIN_SIDE || bounds.outWidth > RailCovers.MAX_SIDE || bounds.outHeight > RailCovers.MAX_SIDE) return null
        val options = BitmapFactory.Options().apply { inSampleSize = RailCovers.sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * A JPEG has no transparency: a transparent picture (a logo, a map, a symbol on nothing) would turn black where it's
     * clear. Flattened onto white, as Wikipedia shows it.
     */
    private fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** The shorter side of a kept thumbnail, about three card widths on a phone. */
        const val TARGET_PX = 400

        /** About 1,000 thumbnails: far more than a month of Home's daily draws. */
        const val MAX_CACHE_BYTES = 50L * 1024 * 1024
        const val MIN_SIDE = PlateRule.MIN_SIDE
        private const val MAX_TRIED = PlateRule.MAX_TRIED
        private const val WHOLE_SUFFIX = app.granger.explore.ThumbnailNames.WHOLE_SUFFIX

        /** The picture rule (Plan EX P0), shared since Plan I3: see [PlateRule.candidates]. */
        fun candidates(html: String, articlePath: String): List<String> = PlateRule.candidates(html, articlePath)

        fun isWhole(thumbnail: String?): Boolean = PlateRule.isWhole(thumbnail)

        fun head(html: String): String = PlateRule.head(html)
    }
}
