package app.granger.core.books

import android.graphics.BitmapFactory
import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

internal actual fun platformComicPages(): ComicPages = AndroidComicPages

/** CBZ through java.util.zip, CBR through junrar, the PDF through PdfBox, as before Plan I5. */
internal object AndroidComicPages : ComicPages {
    private const val JPEG_QUALITY = 0.9f

    override fun unpack(archive: String, scratch: String, keep: (String) -> Boolean): List<Pair<String, String>>? {
        val source = File(archive)
        val dir = File(scratch)
        val head = source.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        val named = mutableListOf<Pair<String, String>>()
        when {
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ZipFile(source).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !keep(entry.name)) continue
                    val file = File(dir, "${named.size}")
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    named += entry.name to file.path
                }
            }
            String(head, Charsets.ISO_8859_1) == "Rar!" -> try {
                Archive(source).use { rar ->
                    // In the archive's own order: a solid archive can't be unpacked any other way.
                    for (header in rar.fileHeaders) {
                        if (header.isDirectory || !keep(header.fileName)) continue
                        val file = File(dir, "${named.size}")
                        file.outputStream().use { rar.extractFile(header, it) }
                        named += header.fileName to file.path
                    }
                }
            } catch (e: RarException) {
                throw IOException(e)
            }
            else -> return null
        }
        return named
    }

    override fun writePdf(pictures: List<String>, out: String): Boolean =
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (picture in pictures) {
                val image = try { image(doc, File(picture)) } catch (_: IOException) { null } ?: continue
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
            }
            if (doc.numberOfPages == 0) return false
            doc.save(File(out))
            true
        }

    private fun image(doc: PDDocument, picture: File): PDImageXObject? {
        val bytes = picture.readBytes()
        if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return JPEGFactory.createFromByteArray(doc, bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            JPEGFactory.createFromImage(doc, bitmap, JPEG_QUALITY)
        } finally {
            bitmap.recycle()
        }
    }
}
