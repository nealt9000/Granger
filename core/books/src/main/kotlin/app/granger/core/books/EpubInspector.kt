package app.granger.core.books

import java.io.File
import java.net.URI
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

class EpubMeta(
    val title: String,
    val authors: List<String>,
    val translators: List<String>,
    val date: String,
    val language: String,
    val subjects: List<String>,
    val description: String,
    val identifier: String,
    val collection: String?,
    val cover: ByteArray?,
    val coverExtension: String?,
    /** Zip paths in reading order. */
    val spine: List<String>,
)

sealed interface Inspection {
    data class Ok(val meta: EpubMeta) : Inspection
    data class Protected(val kind: DrmKind) : Inspection
    data class Invalid(val reason: String) : Inspection
}

object EpubInspector {
    fun inspect(file: File, fallbackTitle: String = file.nameWithoutExtension): Inspection {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            return Inspection.Invalid("Not a zip file")
        } catch (e: java.io.IOException) {
            return Inspection.Invalid("Cannot read file: ${e.message}")
        }
        return zip.use {
            try {
                inspect(it, fallbackTitle)
            } catch (e: java.io.IOException) {
                Inspection.Invalid("Corrupt EPUB: ${e.message}")
            } catch (e: RuntimeException) {
                Inspection.Invalid("Corrupt EPUB: ${e.message}")
            }
        }
    }

    private fun inspect(zip: ZipFile, fallbackTitle: String): Inspection {
        DrmCheck.check(zip)?.let { return Inspection.Protected(it) }
        val mime = zip.getEntry("mimetype")?.let { readCapped(zip, it, MIMETYPE_LIMIT).toString(Charsets.UTF_8).trim() }
        if (mime != "application/epub+zip") return Inspection.Invalid("Missing EPUB mimetype")
        val container = xml(zip, "META-INF/container.xml") ?: return Inspection.Invalid("Missing container.xml")
        val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: return Inspection.Invalid("container.xml names no package document")
        val opf = xml(zip, opfPath) ?: return Inspection.Invalid("Missing package document")

        val manifest = opf.getElementsByTag("item").associateBy { it.attr("id") }
        val spine = opf.getElementsByTag("itemref").mapNotNull { manifest[it.attr("idref")]?.attr("href") }
            .map { resolve(opfPath, it) }
        if (spine.isEmpty()) return Inspection.Invalid("Empty spine")

        val coverItem = opf.getElementsByTag("item").firstOrNull { "cover-image" in it.attr("properties").split(' ') }
            ?: opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }
                ?.let { manifest[it.attr("content")] }
        val coverPath = coverItem?.attr("href")?.let { resolve(opfPath, it) }
        val cover = coverPath?.let { p ->
            zip.getEntry(p)?.let { e -> try { readCapped(zip, e, COVER_LIMIT) } catch (_: java.io.IOException) { null } }
        }

        fun texts(tag: String) = opf.getElementsByTag(tag).map { it.text().trim() }.filter { it.isNotEmpty() }
        val translators = opf.getElementsByTag("dc:contributor").filter { it.attr("opf:role") == "trl" }
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        val collection = opf.getElementsByTag("meta").firstOrNull { it.attr("property") == "belongs-to-collection" }
            ?.text()?.trim()
            ?: opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "calibre:series" }?.attr("content")?.trim()
        return Inspection.Ok(
            EpubMeta(
                title = texts("dc:title").firstOrNull() ?: fallbackTitle,
                authors = texts("dc:creator"),
                translators = translators,
                date = texts("dc:date").firstOrNull().orEmpty(),
                language = texts("dc:language").firstOrNull().orEmpty(),
                subjects = texts("dc:subject"),
                description = texts("dc:description").firstOrNull().orEmpty(),
                identifier = texts("dc:identifier").firstOrNull().orEmpty(),
                collection = collection?.takeIf { it.isNotEmpty() },
                cover = cover,
                coverExtension = if (cover != null) coverPath.substringAfterLast('.', "").lowercase().ifEmpty { null } else null,
                spine = spine,
            ),
        )
    }

    private fun xml(zip: ZipFile, path: String): Document? {
        val entry = zip.getEntry(path) ?: return null
        return Jsoup.parse(readCapped(zip, entry, XML_LIMIT).inputStream(), "UTF-8", "", Parser.xmlParser())
    }

    /** Resolves a manifest href against the package document's folder into a zip path. */
    private fun resolve(opfPath: String, href: String): String {
        val decoded = try { URI(href).path ?: href } catch (_: Exception) { href }
        val parts = ArrayDeque(opfPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() })
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
        }
        return parts.joinToString("/")
    }
}
