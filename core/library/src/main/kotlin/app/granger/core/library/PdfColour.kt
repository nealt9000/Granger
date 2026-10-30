package app.granger.core.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Whether a PDF is in colour: a comic, a magazine, a book of photographs. Night leaves those in their own colours
 * and inverts only black-and-white pages of text, which is what Night is for (Neal, 2026-10-30: Batman in Night).
 */
object PdfColour {
    /** Pages sampled, spread through the document, and the height each is rendered at. */
    private const val SAMPLES = 6
    private const val HEIGHT_PX = 96

    /** A page counts as colour when this share of its pixels is: a few photos or a coloured masthead on a text page don't. */
    private const val COLOUR_PAGE = 0.08f

    /** True when at least half the sampled pages are colour; false if the file can't be rendered. */
    fun isColour(file: File): Boolean = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val count = renderer.pageCount
                if (count == 0) return false
                val pages = (0 until minOf(SAMPLES, count)).map { it * count / minOf(SAMPLES, count) }.distinct()
                val colour = pages.count { index ->
                    renderer.openPage(index).use { page ->
                        val width = (HEIGHT_PX.toFloat() * page.width / page.height).toInt().coerceIn(1, HEIGHT_PX * 2)
                        val bitmap = Bitmap.createBitmap(width, HEIGHT_PX, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(width * HEIGHT_PX).also { bitmap.getPixels(it, 0, width, 0, 0, width, HEIGHT_PX) }
                        bitmap.recycle()
                        colourShare(pixels) >= COLOUR_PAGE
                    }
                }
                colour * 2 >= pages.size
            }
        }
    } catch (_: Exception) {
        false
    } catch (_: OutOfMemoryError) {
        false
    }

    /**
     * The share of [pixels] (ARGB) that are clearly coloured: saturated and neither near black nor near white. Old
     * paper's yellow and a scan's grey tint stay under the saturation bar.
     */
    fun colourShare(pixels: IntArray): Float {
        if (pixels.isEmpty()) return 0f
        var coloured = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 40) continue
            val saturation = (max - min).toFloat() / max
            if (saturation >= 0.35f && max - min >= 40) coloured++
        }
        return coloured.toFloat() / pixels.size
    }
}
