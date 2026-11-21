package app.granger.core.books

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUB zips in a folder for tests. Every option has a sensible default. */
object TestEpubs {
    enum class CoverStyle { NONE, EPUB3_PROPERTY, EPUB2_META }

    val COVER_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    fun xhtml(body: String, title: String = "Chapter"): String =
        """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>$body</body></html>"""

    /**
     * @param title null or blank writes no usable `dc:title`.
     * @param chapters XHTML body fragments, one per spine item (`OEBPS/c001.xhtml`, ...).
     * @param drmFiles extra zip entries by path (for example `META-INF/license.lcpl`).
     * @param includeOpf false leaves out the package document (container.xml still points at it).
     * @param epub3Roles true gives each author and translator an id and an EPUB 3
     *   `<meta refines="#id" property="role" scheme="marc:relators">` (`aut`, `trl`) instead of `opf:role`.
     * @param translatorTag the element translators are written as (`dc:contributor`, or `dc:creator` as some EPUB 3
     *   books do).
     */
    fun build(
        dir: File,
        name: String = "book.epub",
        title: String? = "Test Book",
        authors: List<String> = listOf("Ann Author"),
        translators: List<String> = emptyList(),
        language: String = "en",
        subjects: List<String> = emptyList(),
        description: String = "",
        date: String = "2020-01-02",
        identifier: String = "urn:uuid:11111111-2222-3333-4444-555555555555",
        collection: String? = null,
        cover: CoverStyle = CoverStyle.NONE,
        coverBytes: ByteArray = COVER_BYTES,
        /** The cover's manifest href and zip path under `OEBPS/`. */
        coverHref: String = "cover.png",
        chapters: List<String> = listOf("<p>Hello world.</p>"),
        drmFiles: Map<String, String> = emptyMap(),
        includeOpf: Boolean = true,
        epub3Roles: Boolean = false,
        translatorTag: String = "dc:contributor",
    ): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { z ->
            val mime = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().also { it.update(mime) }.value
            })
            z.write(mime)
            z.closeEntry()
            z.put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            if (includeOpf) {
                z.put("OEBPS/content.opf", opf(title, authors, translators, language, subjects, description, date, identifier, collection, cover, chapters.size, coverHref, epub3Roles, translatorTag).toByteArray())
            }
            chapters.forEachIndexed { i, body -> z.put("OEBPS/c%03d.xhtml".format(i + 1), xhtml(body).toByteArray()) }
            if (cover != CoverStyle.NONE) z.put("OEBPS/$coverHref", coverBytes)
            drmFiles.forEach { (path, content) -> z.put(path, content.toByteArray()) }
        }
        return file
    }

    /** Overwrites the start of [entryName]'s compressed data so reading it fails. */
    fun corrupt(file: File, entryName: String) {
        val bytes = file.readBytes()
        val name = entryName.toByteArray()
        var i = 0
        while (i + 30 < bytes.size) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()) {
                fun u16(o: Int) = (bytes[i + o].toInt() and 0xFF) or ((bytes[i + o + 1].toInt() and 0xFF) shl 8)
                val nameLen = u16(26)
                val dataStart = i + 30 + nameLen + u16(28)
                if (nameLen == name.size && bytes.copyOfRange(i + 30, i + 30 + nameLen).contentEquals(name)) {
                    for (k in dataStart until minOf(dataStart + 8, bytes.size)) bytes[k] = 0xFF.toByte()
                    file.writeBytes(bytes)
                    return
                }
            }
            i++
        }
        error("No entry $entryName")
    }

    private fun ZipOutputStream.put(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun opf(
        title: String?, authors: List<String>, translators: List<String>, language: String,
        subjects: List<String>, description: String, date: String, identifier: String,
        collection: String?, cover: CoverStyle, chapterCount: Int, coverHref: String,
        epub3Roles: Boolean, translatorTag: String,
    ): String {
        val meta = StringBuilder()
        meta.append("<dc:identifier id=\"id\">${esc(identifier)}</dc:identifier>")
        if (title != null) meta.append("<dc:title>${esc(title)}</dc:title>")
        if (epub3Roles) {
            fun refined(tag: String, id: String, name: String, role: String) = meta.append(
                "<$tag id=\"$id\">${esc(name)}</$tag>" +
                    "<meta refines=\"#$id\" property=\"role\" scheme=\"marc:relators\">$role</meta>",
            )
            authors.forEachIndexed { i, name -> refined("dc:creator", "aut$i", name, "aut") }
            translators.forEachIndexed { i, name -> refined(translatorTag, "trl$i", name, "trl") }
        } else {
            authors.forEach { meta.append("<dc:creator>${esc(it)}</dc:creator>") }
            translators.forEach { meta.append("<$translatorTag opf:role=\"trl\">${esc(it)}</$translatorTag>") }
        }
        meta.append("<dc:language>$language</dc:language><dc:date>$date</dc:date>")
        subjects.forEach { meta.append("<dc:subject>${esc(it)}</dc:subject>") }
        if (description.isNotEmpty()) meta.append("<dc:description>${esc(description)}</dc:description>")
        if (collection != null) meta.append("<meta property=\"belongs-to-collection\" id=\"c1\">${esc(collection)}</meta>")
        if (cover == CoverStyle.EPUB2_META) meta.append("<meta name=\"cover\" content=\"cover-img\"/>")
        val manifest = StringBuilder()
        if (cover != CoverStyle.NONE) {
            val props = if (cover == CoverStyle.EPUB3_PROPERTY) " properties=\"cover-image\"" else ""
            manifest.append("<item id=\"cover-img\" href=\"${esc(coverHref)}\" media-type=\"image/png\"$props/>")
        }
        val spine = StringBuilder()
        for (i in 1..chapterCount) {
            manifest.append("<item id=\"c$i\" href=\"c%03d.xhtml\" media-type=\"application/xhtml+xml\"/>".format(i))
            spine.append("<itemref idref=\"c$i\"/>")
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata>$meta</metadata><manifest>$manifest</manifest><spine>$spine</spine></package>"""
    }
}
