package app.granger.core.books

import android.graphics.BitmapFactory
import app.granger.core.text.NaturalOrder
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
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Turns a comic book archive (CBZ, a zip; CBR, a RAR 4 archive) into a PDF of its pictures, one page each at the
 * picture's own size, in page order, so a comic reads in the PDF reader with its zoom. JPEGs go in as they are, never
 * re-encoded; other pictures (PNG, GIF, WebP, BMP) are decoded and stored as JPEG. The archive's kind is read from
 * its first bytes, not its name: plenty of `.cbr` files are zips.
 */
object ComicToPdf {
    fun isComicName(name: String): Boolean = name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") }

    /**
     * Writes [archive]'s pictures to [out] (through a `.incoming-` partial beside it); false, with no [out] written,
     * when the archive can't be read (a RAR 5 archive among them) or holds no picture. The pictures are unpacked to a
     * scratch folder beside [out] first (a solid RAR only unpacks in its own order) and removed after.
     */
    fun convert(archive: File, out: File): Boolean {
        val dir = out.absoluteFile.parentFile
        val scratch = File(dir, ".incoming-${UUID.randomUUID()}")
        val partial = File(dir, ".incoming-${UUID.randomUUID()}.pdf")
        return try {
            scratch.mkdirs()
            val pages = unpack(archive, scratch) ?: return false
            if (!write(pages, partial)) return false
            if (!partial.renameTo(out)) {
                partial.copyTo(out, overwrite = true)
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: RarException) {
            false
        } catch (_: RuntimeException) {
            // junrar and PdfBox both throw unchecked exceptions on damaged input.
            false
        } finally {
            partial.delete()
            scratch.deleteRecursively()
        }
    }

    /** The archive's pictures unpacked into [scratch], in page order; null if it isn't a zip or RAR archive. */
    private fun unpack(archive: File, scratch: File): List<File>? {
        val head = archive.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        val named = mutableListOf<Pair<String, File>>()
        when {
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ZipFile(archive).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !isPicture(entry.name)) continue
                    val file = File(scratch, "${named.size}")
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    named += entry.name to file
                }
            }
            String(head, Charsets.ISO_8859_1) == "Rar!" -> Archive(archive).use { rar ->
                // In the archive's own order: a solid archive can't be unpacked any other way.
                for (header in rar.fileHeaders) {
                    if (header.isDirectory || !isPicture(header.fileName)) continue
                    val file = File(scratch, "${named.size}")
                    file.outputStream().use { rar.extractFile(header, it) }
                    named += header.fileName to file
                }
            }
            else -> return null
        }
        return named.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }.map { it.second }
    }

    /** One page per picture that reads; false when none does. */
    private fun write(pages: List<File>, out: File): Boolean =
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (picture in pages) {
                val image = try { image(doc, picture) } catch (_: IOException) { null } ?: continue
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
            }
            if (doc.numberOfPages == 0) return false
            doc.save(out)
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

    /** A picture by its name, outside the folders macOS and Windows leave in archives. */
    private fun isPicture(path: String): Boolean {
        val name = path.replace('\\', '/')
        if (name.split('/').any { it.startsWith(".") || it == "__MACOSX" }) return false
        return name.substringAfterLast('.', "").lowercase() in PICTURES
    }

    private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private const val JPEG_QUALITY = 0.9f
}
