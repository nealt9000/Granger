package app.granger.core.books

import app.granger.core.text.NaturalOrder
import kotlinx.io.IOException

/**
 * Turns a comic book archive (CBZ, a zip; CBR, a RAR 4 archive) into a PDF of its pictures, one page each at the
 * picture's own size, in page order, so a comic reads in the PDF reader with its zoom. JPEGs go in as they are, never
 * re-encoded; other pictures (PNG, GIF, WebP, BMP) are decoded and stored as JPEG. The archive's kind is read from
 * its first bytes, not its name: plenty of `.cbr` files are zips. The phone's [ComicPages] unpack and write.
 */
object ComicToPdf {
    fun isComicName(name: String): Boolean = name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") }

    /**
     * Writes [archive]'s pictures to [out] (through a `.incoming-` partial beside it); false, with no [out] written,
     * when the archive can't be read (a RAR 5 archive among them) or holds no picture. The pictures are unpacked to a
     * scratch folder beside [out] first (a solid RAR only unpacks in its own order) and removed after.
     */
    fun convert(archive: String, out: String): Boolean {
        val dir = parentFolder(out)
        val scratch = "$dir/.incoming-${randomUuid()}"
        val partial = "$dir/.incoming-${randomUuid()}.pdf"
        val comics = BookEngines.comics
        return try {
            makeFolders(scratch)
            val pages = comics.unpack(archive, scratch, ::isPicture)
                ?.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
                ?.map { it.second }
                ?: return false
            if (!comics.writePdf(pages, partial)) return false
            moveOrCopy(partial, out)
            true
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            // junrar and PdfBox both throw unchecked exceptions on damaged input.
            false
        } finally {
            deleteAll(partial)
            deleteAll(scratch)
        }
    }

    /** A picture by its name, outside the folders macOS and Windows leave in archives. */
    internal fun isPicture(path: String): Boolean {
        val name = path.replace('\\', '/')
        if (name.split('/').any { it.startsWith(".") || it == "__MACOSX" }) return false
        return name.substringAfterLast('.', "").lowercase() in PICTURES
    }

    private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}
