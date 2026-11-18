package app.granger.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.granger.core.zim.ZimArchive
import app.granger.shell.RailCovers
import java.io.File
import java.security.MessageDigest
import org.jsoup.Jsoup

/**
 * A card's picture for a Kiwix article: the article's lead image, read out of the ZIM once and kept as a small JPEG
 * under the app's cache (the OS may clear it; the card then shows its icon). Blocking; call off the main thread.
 */
class ArticleThumbnails(private val dir: File) {
    /** The cached file's path, or null when the article has no usable image; [targetPx] sizes it (a topic page's header is larger). */
    fun thumbnail(archive: ZimArchive, articlePath: String, html: String, targetPx: Int = TARGET_PX): String? = try {
        // The first that the pack holds and that is big enough as stored: a pack can keep a drawing far smaller than the
        // page draws it, and the next picture then stands (as the picture rule was graded, Plan EX P0).
        ranked(html, articlePath).asSequence().mapNotNull { (path, whole) -> archive.resolvePath(path)?.let { it to whole } }.distinctBy { it.first }.take(MAX_TRIED)
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
        private const val MIN_SIDE = 64
        private val SKIPPED_EXTENSIONS = setOf("svg", "ico")

        /** How many of an article's pictures the rule looks at, and how many it will read before giving up. */
        private const val CONSIDERED = 8
        private const val MAX_TRIED = 4
        private const val ROOMY_WIDTH = 120
        private const val ROOMY_HEIGHT = 100

        private fun word(s: String) = "(?<![a-z])(?:$s)(?![a-z])"

        /** A picture whose file name says flag, seal, map or the like: never the photo a series symbol gives way to. */
        private val EMBLEM = Regex(
            listOf(
                word("flags?"), "coat[_ ]of[_ ]arms", word("coa"), "seal[_ ]of", word("emblem"), word("escudo"), word("bandera"), word("wappen"),
                word("blason"), "locator", "location[_ ]map", word("maps?"), "orthographic", word("projection"), word("signature"), "diaspora",
                word("insignia"), word("crest"), word("karte"), word("carte"), "carta[_ ](?:nav|geo|de)", word("charts?"),
            ).joinToString("|"),
            RegexOption.IGNORE_CASE,
        )

        /** An article about a symbol itself (Flag of Washington, Coat of arms of…): its drawn symbol is its picture. */
        private val SYMBOL_TOPIC =
            Regex(word("flags?|coat of arms|seal|emblem|maps?|logo|insignia|banner|symbols?|heraldry|cartography|atlas"), RegexOption.IGNORE_CASE)

        private class Found(val src: String, val name: String, val width: Int?, val height: Int?, val kind: String, val drawing: Boolean) {
            val emblem get() = EMBLEM.containsMatchIn(name) || kind == "chrome" || (kind == "sidebar" && drawing)
            val jpeg get() = name.lowercase().removeSuffix(".webp").substringAfterLast('.', "") in setOf("jpg", "jpeg")
            val roomy get() = (width ?: 0) >= ROOMY_WIDTH && (height ?: 0) >= ROOMY_HEIGHT
            val wide get() = (width ?: 0) >= (height ?: 0)
        }

        private fun decoded(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** What holds the picture, by its nearest telling ancestor: a figure, the infobox, a series sidebar, page chrome… */
        private fun kind(img: org.jsoup.nodes.Element): String {
            for (a in img.parents()) {
                val cls = a.className()
                val table = a.tagName() == "table"
                return when {
                    a.tagName() == "figure" -> "figure"
                    "infobox-image" in cls -> "infobox"
                    "sidebar" in cls -> "sidebar"
                    "multiimage" in cls || "tsingle" in cls -> "multi"
                    "gallery" in cls -> "gallery"
                    "navbox" in cls || "metadata" in cls || "ambox" in cls || "noprint" in cls -> "chrome"
                    table -> "table"
                    else -> continue
                }
            }
            return "other"
        }

        /**
         * The ZIM paths that may hold the article's lead image, best first (the picture rule, Plan EX P0, graded with Neal
         * 2026-11-17): the article's pictures in page order, skipping icons, formulas, data URIs and anything drawn
         * under 64 px; except that when the first is a series sidebar's drawn symbol (a Dharma wheel on every Buddhism
         * page, a coat of arms on "Constitution of…") and the article isn't about a symbol, the first roomy JPEG photo among
         * the first eight goes ahead of it, wide before tall. Each picture gives two paths: resolved against the article's
         * path (`../I/x.jpg` from `A/Cat`, `I/x.jpg` from `Cat`), then the bare `I/<name>` form, for either layout.
         */
        fun candidates(html: String, articlePath: String): List<String> = ranked(html, articlePath).map { it.first }

        private const val WHOLE_SUFFIX = ".w.jpg"

        /**
         * Whether a thumbnail made by [thumbnail] is of a drawing or an emblem (a flag, a seal, a logo, a map, a diagram):
         * one to show whole on a plain ground, never cropped (Plan EX P0).
         */
        fun isWhole(thumbnail: String?): Boolean = thumbnail?.endsWith(WHOLE_SUFFIX) == true

        /** [candidates], each with whether it is a drawing or an emblem. */
        private fun ranked(html: String, articlePath: String): List<Pair<String, Boolean>> {
            val doc = Jsoup.parse(html)
            val found = ArrayList<Found>()
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
                found += Found(src, decoded(decoded(src)).substringAfterLast('/'), w, h, kind(img), img.attr("data-file-type") == "drawing")
                if (found.size >= CONSIDERED) break
            }
            val first = found.firstOrNull() ?: return emptyList()
            val title = decoded(articlePath).substringAfterLast('/').replace('_', ' ')
            val ahead = if (first.kind == "sidebar" && first.drawing && !SYMBOL_TOPIC.containsMatchIn(title)) {
                found.drop(1).filter { !it.drawing && !it.emblem && it.jpeg && it.roomy }.let { photos -> photos.firstOrNull { it.wide } ?: photos.firstOrNull() }
            } else null
            val out = LinkedHashMap<String, Boolean>()
            for (f in listOfNotNull(ahead) + found) {
                val whole = f.drawing || EMBLEM.containsMatchIn(f.name)
                out.putIfAbsent(ZimPaths.resolve(f.src, articlePath), whole)
                out.putIfAbsent("I/" + f.src.substringAfterLast('/'), whole)
            }
            return out.toList()
        }
    }
}
