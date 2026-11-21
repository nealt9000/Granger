package app.granger.core.books

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

actual typealias BookZip = ZipFile

actual typealias BookOutput = java.io.OutputStream

actual fun openBookZip(path: String): BookZip = try {
    ZipFile(File(path))
} catch (e: ZipException) {
    throw NotAZipException(e.message)
}

internal actual fun BookZip.has(name: String): Boolean = getEntry(name) != null

internal actual fun BookZip.readCapped(name: String, limit: Int): ByteArray? {
    val entry = getEntry(name) ?: return null
    getInputStream(entry).use { input ->
        val out = ByteArrayOutputStream(minOf(limit.toLong(), maxOf(entry.size, 0L) + 1).toInt().coerceAtLeast(32))
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("Entry ${entry.name} is larger than $limit bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

internal actual class EpubZip actual constructor(out: BookOutput) : AutoCloseable {
    private val z = ZipOutputStream(out)

    actual fun stored(name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        })
        z.write(bytes)
        z.closeEntry()
    }

    actual fun put(name: String, bytes: ByteArray) {
        z.putNextEntry(ZipEntry(name))
        z.write(bytes)
        z.closeEntry()
    }

    actual fun putFile(name: String, path: String) {
        z.putNextEntry(ZipEntry(name))
        File(path).inputStream().use { it.copyTo(z) }
        z.closeEntry()
    }

    override fun close() = z.close()
}

actual fun outputTo(path: String): BookOutput = FileOutputStream(File(path))

internal actual fun parentFolder(path: String): String = File(path).absoluteFile.parentFile!!.path

internal actual fun makeFolders(path: String) {
    File(path).mkdirs()
}

internal actual fun deleteAll(path: String) {
    File(path).deleteRecursively()
}

internal actual fun fileLength(path: String): Long = File(path).length()

internal actual fun moveOrCopy(from: String, to: String) {
    val partial = File(from)
    val out = File(to)
    if (!partial.renameTo(out)) partial.copyTo(out, overwrite = true)
}

internal actual fun nameUuid(bytes: ByteArray): String = UUID.nameUUIDFromBytes(bytes).toString()

internal actual fun randomUuid(): String = UUID.randomUUID().toString()

internal actual fun nowIsoSeconds(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

internal actual fun uriPath(href: String): String = try { URI(href).path ?: href } catch (_: Exception) { href }

internal actual fun decodeText(bytes: ByteArray): String {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return strictUtf8(bytes, 3) ?: cp1252(bytes, 3)
    if (at(0) == 0xFF && at(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (at(0) == 0xFE && at(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    return strictUtf8(bytes, 0) ?: cp1252(bytes, 0)
}

private fun cp1252(bytes: ByteArray, from: Int) = String(bytes, from, bytes.size - from, charset("windows-1252"))

private fun strictUtf8(bytes: ByteArray, from: Int): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
} catch (_: CharacterCodingException) {
    null
}
