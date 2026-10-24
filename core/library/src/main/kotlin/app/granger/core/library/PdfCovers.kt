package app.granger.core.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** A PDF has no cover entry; its first page stands in. */
fun interface PdfCovers {
    /** Page 1 as a PNG [heightPx] tall; null if the file can't be rendered. */
    fun firstPage(file: File, heightPx: Int): ByteArray?
}

/** The platform's renderer: a cover shouldn't need the reader's engine, and this one is always there. */
object PlatformPdfCovers : PdfCovers {
    override fun firstPage(file: File, heightPx: Int): ByteArray? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val width = (heightPx.toFloat() * page.width / page.height).toInt().coerceIn(1, heightPx * 2)
                    val bitmap = Bitmap.createBitmap(width, heightPx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        bitmap.recycle()
                        out.toByteArray()
                    }
                }
            }
        }
    } catch (_: Exception) {
        // A PDF the platform renderer rejects (some it can't parse that PDFium can) just has no cover.
        null
    }
}
