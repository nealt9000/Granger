@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package app.granger.core.books

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** An EPUB's destination on the iPhone: a kotlinx-io [Sink] ([SinkOutput]), or a file ([outputTo]). */
actual abstract class BookOutput : AutoCloseable {
    abstract fun write(bytes: ByteArray, startIndex: Int = 0, endIndex: Int = bytes.size)

    actual override fun close() {}
}

/** Writes into [sink] and closes it when closed. */
class SinkOutput(private val sink: Sink) : BookOutput() {
    override fun write(bytes: ByteArray, startIndex: Int, endIndex: Int) = sink.write(bytes, startIndex, endIndex)

    override fun close() = sink.close()
}

actual fun outputTo(path: String): BookOutput = SinkOutput(SystemFileSystem.sink(Path(path)).buffered())

internal actual fun parentFolder(path: String): String {
    val absolute = if (path.startsWith("/")) path else NSFileManager.defaultManager.currentDirectoryPath.trimEnd('/') + "/" + path
    return absolute.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
}

internal actual fun makeFolders(path: String) {
    try { SystemFileSystem.createDirectories(Path(path)) } catch (_: IOException) {}
}

internal actual fun deleteAll(path: String) {
    val p = Path(path)
    val meta = try { SystemFileSystem.metadataOrNull(p) } catch (_: IOException) { null } ?: return
    if (meta.isDirectory) {
        try { SystemFileSystem.list(p) } catch (_: IOException) { emptyList() }.forEach { deleteAll(it.toString()) }
    }
    try { SystemFileSystem.delete(p, mustExist = false) } catch (_: IOException) {}
}

internal actual fun fileLength(path: String): Long =
    try { SystemFileSystem.metadataOrNull(Path(path))?.takeIf { it.isRegularFile }?.size } catch (_: IOException) { null } ?: 0L

internal actual fun moveOrCopy(from: String, to: String) {
    try {
        SystemFileSystem.atomicMove(Path(from), Path(to))
        return
    } catch (_: IOException) {
        // Another volume: copied below.
    }
    val bytes = SystemFileSystem.source(Path(from)).buffered().use { it.readByteArray() }
    SystemFileSystem.sink(Path(to)).buffered().use { it.write(bytes) }
}

internal actual fun nameUuid(bytes: ByteArray): String {
    val md5 = UByteArray(CC_MD5_DIGEST_LENGTH)
    md5.usePinned { out ->
        if (bytes.isEmpty()) {
            CC_MD5(null, 0u, out.addressOf(0))
        } else {
            bytes.usePinned { input -> CC_MD5(input.addressOf(0), bytes.size.convert(), out.addressOf(0)) }
        }
    }
    val b = md5.toByteArray()
    b[6] = ((b[6].toInt() and 0x0F) or 0x30).toByte()
    b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()

internal actual fun nowIsoSeconds(): String = Instant.fromEpochSeconds(Clock.System.now().epochSeconds).toString()

/** As `java.net.URI(href).path`: the path without query or fragment, percent-decoded; [href] itself when it isn't a valid URI. */
internal actual fun uriPath(href: String): String {
    if (href.any { it <= ' ' || it in "\"<>\\^`{|}" || it == '\u007F' }) return href
    var rest = href.substringBefore('#')
    rest = rest.substringBefore('?')
    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(rest)
    if (scheme != null) {
        rest = rest.substring(scheme.value.length)
        if (rest.startsWith("//")) rest = rest.substring(2).let { a -> a.indexOf('/').let { if (it < 0) "" else a.substring(it) } }
    }
    return percentDecode(rest) ?: href
}

private fun percentDecode(s: String): String? {
    if ('%' !in s) return s
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            if (i + 2 >= s.length) return null
            val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            out += v.toByte()
            i += 3
        } else {
            c.toString().encodeToByteArray().forEach { out += it }
            i++
        }
    }
    return out.toByteArray().decodeToString()
}

internal actual fun decodeText(bytes: ByteArray): String {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return strictUtf8(bytes, 3) ?: cp1252(bytes, 3)
    if (at(0) == 0xFF && at(1) == 0xFE) return utf16(bytes, 2, bigEndian = false)
    if (at(0) == 0xFE && at(1) == 0xFF) return utf16(bytes, 2, bigEndian = true)
    return strictUtf8(bytes, 0) ?: cp1252(bytes, 0)
}

private fun strictUtf8(bytes: ByteArray, from: Int): String? = try {
    bytes.decodeToString(from, bytes.size, throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}

/** Windows-1252's 0x80–0x9F; the five it leaves undefined become U+FFFD, as Java decodes them. */
private const val CP1252_HIGH =
    "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž��‘’“”•–—˜™š›œ�žŸ"

private fun cp1252(bytes: ByteArray, from: Int): String {
    val sb = StringBuilder(bytes.size - from)
    for (i in from until bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        sb.append(if (b in 0x80..0x9F) CP1252_HIGH[b - 0x80] else b.toChar())
    }
    return sb.toString()
}

internal fun crc32Of(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return 0
    return bytes.usePinned { platform.zlib.crc32(0u, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert()).toInt() }
}

internal actual fun platformPdfOpener(): PdfOpener = PdfOpener {
    throw UnsupportedOperationException("I5b: the iPhone's PDF reader (PDFKit) isn't set; set BookEngines.pdf at launch")
}

/** Markdown on the iPhone: the shared [PlainMarkdown], to Android's chapter rules (Plan I5 Task 6). */
internal actual fun platformMarkdown(): MarkdownChapters = PlainMarkdown

internal actual fun platformComicPages(): ComicPages = IosComicPages

/**
 * Comics on the iPhone (Plan I5 call 6): CBZ through the shared zip reader here; CBR (RAR 4) and writing the PDF are
 * Swift's (libarchive and UIGraphicsPDFRenderer), handed over once at launch with [useNatives]. Until then a RAR
 * archive or a PDF can't be made and the conversion fails as a damaged file does.
 */
object IosComicPages : ComicPages {
    /** What Swift does for the comics: unpack a RAR archive, and draw pictures into a PDF. */
    interface Natives {
        /**
         * Unpacks [archive] (a RAR archive) into [scratch], in the archive's own order, each kept entry ([keep]) as
         * `<scratch>/<n>`; its name in the archive and the file's path, in pairs. Null when it can't be read.
         */
        fun unpackRar(archive: String, scratch: String, keep: (String) -> Boolean): List<String>?

        /** As [ComicPages.writePdf]. */
        fun writePdf(pictures: List<String>, out: String): Boolean
    }

    private var natives: Natives? = null

    fun useNatives(natives: Natives) {
        this.natives = natives
        BookEngines.comics = this
    }

    override fun unpack(archive: String, scratch: String, keep: (String) -> Boolean): List<Pair<String, String>>? {
        val head = SystemFileSystem.source(Path(archive)).buffered().use { it.readByteArray(minOf(4L, fileLength(archive)).toInt()) }
        if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val named = mutableListOf<Pair<String, String>>()
            openBookZip(archive).use { zip ->
                for (name in zip.names) {
                    if (name.endsWith("/") || !keep(name)) continue
                    val bytes = zip.readCapped(name, Int.MAX_VALUE) ?: continue
                    val file = "$scratch/${named.size}"
                    SystemFileSystem.sink(Path(file)).buffered().use { it.write(bytes) }
                    named += name to file
                }
            }
            return named
        }
        if (head.size == 4 && head.decodeToString() == "Rar!") {
            val flat = natives?.unpackRar(archive, scratch, keep) ?: throw IOException("can't read the RAR archive $archive")
            return flat.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
        }
        return null
    }

    override fun writePdf(pictures: List<String>, out: String): Boolean =
        natives?.writePdf(pictures, out) ?: throw IOException("no PDF writer for comics on this iPhone yet")
}
