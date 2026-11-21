@file:OptIn(ExperimentalForeignApi::class)

package app.granger.core.books

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.FILE
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/*
 * A small zip reader and writer for the iPhone (EPUBs and CBZ): stored and deflated entries, no ZIP64 (an EPUB or a
 * comic under 4 GB), no encryption. Android keeps java.util.zip.
 */

/** One entry from the central directory. */
internal class ZipEntryInfo(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)

actual class BookZip internal constructor(private val file: CPointer<FILE>, private val entries: List<ZipEntryInfo>) : AutoCloseable {
    private val byName = entries.associateBy { it.name }

    /** The entries' names, in the archive's order. */
    internal val names: List<String> get() = entries.map { it.name }

    internal fun entry(name: String): ZipEntryInfo? = byName[name]

    override fun close() {
        fclose(file)
    }

    internal fun readAt(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        if (length == 0) return out
        if (fseeko(file, offset.convert(), SEEK_SET) != 0) throw IOException("zip: can't seek to $offset")
        val read = out.usePinned { fread(it.addressOf(0), 1u, length.convert(), file).toInt() }
        if (read != length) throw IOException("zip: truncated")
        return out
    }

    internal fun dataOffset(e: ZipEntryInfo): Long {
        val local = readAt(e.localOffset, 30)
        if (local.u32(0) != 0x04034b50L) throw IOException("zip: bad local header for ${e.name}")
        return e.localOffset + 30 + local.u16(26) + local.u16(28)
    }
}

internal actual fun openBookZip(path: String): BookZip {
    val file = fopen(path, "rb") ?: throw IOException("Cannot open $path")
    try {
        fseeko(file, 0, SEEK_END)
        val size = ftello(file).toLong()
        val tailLength = minOf(size, 22L + 65_535L).toInt()
        val probe = BookZip(file, emptyList())
        val tail = probe.readAt(size - tailLength, tailLength)
        var end = -1
        for (i in tailLength - 22 downTo 0) {
            if (tail.u32(i) == 0x06054b50L) { end = i; break }
        }
        if (end < 0) throw NotAZipException("zip END header not found")
        val count = tail.u16(end + 10)
        val cdSize = tail.u32(end + 12)
        val cdOffset = tail.u32(end + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) throw IOException("zip: ZIP64 isn't read here")
        if (cdOffset + cdSize > size) throw NotAZipException("zip: central directory out of range")
        val cd = probe.readAt(cdOffset, cdSize.toInt())
        val entries = ArrayList<ZipEntryInfo>(count)
        var at = 0
        repeat(count) {
            if (at + 46 > cd.size || cd.u32(at) != 0x02014b50L) throw NotAZipException("zip: bad central directory")
            val nameLength = cd.u16(at + 28)
            val name = cd.decodeToString(at + 46, at + 46 + nameLength)
            entries += ZipEntryInfo(name, cd.u16(at + 10), cd.u32(at + 20), cd.u32(at + 24), cd.u32(at + 42))
            at += 46 + nameLength + cd.u16(at + 30) + cd.u16(at + 32)
        }
        return BookZip(file, entries)
    } catch (e: Throwable) {
        fclose(file)
        throw e
    }
}

internal actual fun BookZip.has(name: String): Boolean = entry(name) != null

internal actual fun BookZip.readCapped(name: String, limit: Int): ByteArray? {
    val e = entry(name) ?: return null
    val start = dataOffset(e)
    return when (e.method) {
        0 -> {
            if (e.compressedSize > limit) throw IOException("Entry $name is larger than $limit bytes")
            readAt(start, e.compressedSize.toInt())
        }
        8 -> inflateRaw(this, start, e.compressedSize, limit, name)
        else -> throw IOException("zip: $name uses compression method ${e.method}")
    }
}

private const val CHUNK = 64 * 1024

private fun inflateRaw(zip: BookZip, start: Long, length: Long, limit: Int, name: String): ByteArray {
    val stream = nativeHeap.alloc<z_stream>()
    try {
        if (inflateInit2_(stream.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) throw IOException("zip: inflateInit")
        var out = ByteArray(minOf(maxOf(length * 3, 1024L), limit.toLong() + 1).toInt().coerceAtLeast(1024))
        var produced = 0
        var fed = 0L
        try {
            while (true) {
                val input = if (fed < length) zip.readAt(start + fed, minOf(CHUNK.toLong(), length - fed).toInt()) else ByteArray(0)
                fed += input.size
                var inOffset = 0
                var done = false
                do {
                    if (produced == out.size) {
                        if (out.size > limit) throw IOException("Entry $name is larger than $limit bytes")
                        out = out.copyOf(minOf(out.size.toLong() * 2, limit.toLong() + 1).toInt())
                    }
                    val status = input.usePinned { inp ->
                        out.usePinned { o ->
                            stream.next_in = if (input.isEmpty()) null else inp.addressOf(inOffset).reinterpret()
                            stream.avail_in = (input.size - inOffset).convert()
                            stream.next_out = o.addressOf(produced).reinterpret()
                            stream.avail_out = (out.size - produced).convert()
                            val r = inflate(stream.ptr, Z_NO_FLUSH)
                            inOffset = input.size - stream.avail_in.toInt()
                            produced = out.size - stream.avail_out.toInt()
                            r
                        }
                    }
                    if (produced > limit) throw IOException("Entry $name is larger than $limit bytes")
                    when (status) {
                        Z_STREAM_END -> done = true
                        Z_OK -> {}
                        Z_BUF_ERROR -> if (input.isEmpty()) throw IOException("zip: $name is truncated")
                        else -> throw IOException("zip: $name is corrupt ($status)")
                    }
                } while (!done && (inOffset < input.size || produced == out.size))
                if (done) return out.copyOf(produced)
                if (fed >= length && input.isEmpty()) throw IOException("zip: $name is truncated")
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    } finally {
        nativeHeap.free(stream)
    }
}

internal actual class EpubZip actual constructor(private val out: BookOutput) : AutoCloseable {
    private class Central(val name: ByteArray, val method: Int, val crc: Int, val compressed: Int, val size: Int, val offset: Int)

    private val central = ArrayList<Central>()
    private var offset = 0L

    actual fun stored(name: String, bytes: ByteArray) = entry(name, bytes, stored = true)

    actual fun put(name: String, bytes: ByteArray) = entry(name, bytes, stored = false)

    actual fun putFile(name: String, path: String) =
        entry(name, SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }, stored = false)

    private fun entry(name: String, bytes: ByteArray, stored: Boolean) {
        val nameBytes = name.encodeToByteArray()
        val data = if (stored) bytes else deflateRaw(bytes)
        val crc = crc32Of(bytes)
        val method = if (stored) 0 else 8
        val header = Bytes()
        header.u32(0x04034b50).u16(20).u16(0x0800).u16(method).u16(0).u16(DOS_DATE)
            .u32(crc.toLong()).u32(data.size.toLong()).u32(bytes.size.toLong()).u16(nameBytes.size).u16(0)
        write(header.toByteArray())
        central += Central(nameBytes, method, crc, data.size, bytes.size, offset.toInt())
        write(nameBytes)
        write(data)
        offset += 30 + nameBytes.size + data.size
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
    }

    override fun close() {
        try {
            val start = offset
            for (c in central) {
                val h = Bytes()
                h.u32(0x02014b50).u16(20).u16(20).u16(0x0800).u16(c.method).u16(0).u16(DOS_DATE)
                    .u32(c.crc.toLong()).u32(c.compressed.toLong()).u32(c.size.toLong()).u16(c.name.size).u16(0).u16(0)
                    .u16(0).u16(0).u32(0).u32(c.offset.toLong())
                write(h.toByteArray())
                write(c.name)
                offset += 46 + c.name.size
            }
            val end = Bytes()
            end.u32(0x06054b50).u16(0).u16(0).u16(central.size).u16(central.size).u32(offset - start).u32(start).u16(0)
            write(end.toByteArray())
        } finally {
            out.close()
        }
    }

    private companion object {
        /** 1980-01-01, zip's first day: the entries carry no time of their own. */
        const val DOS_DATE = 0x0021
    }
}

private fun deflateRaw(bytes: ByteArray): ByteArray {
    val stream = nativeHeap.alloc<z_stream>()
    try {
        if (deflateInit2_(stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, sizeOf<z_stream>().convert()) != Z_OK) {
            throw IOException("zip: deflateInit")
        }
        try {
            val out = ByteArray(deflateBound(stream.ptr, bytes.size.convert()).toInt() + 16)
            val status = bytes.usePinned { inp ->
                out.usePinned { o ->
                    stream.next_in = if (bytes.isEmpty()) null else inp.addressOf(0).reinterpret()
                    stream.avail_in = bytes.size.convert()
                    stream.next_out = o.addressOf(0).reinterpret()
                    stream.avail_out = out.size.convert()
                    deflate(stream.ptr, Z_FINISH)
                }
            }
            if (status != Z_STREAM_END) throw IOException("zip: deflate ($status)")
            return out.copyOf(out.size - stream.avail_out.toInt())
        } finally {
            deflateEnd(stream.ptr)
        }
    } finally {
        nativeHeap.free(stream)
    }
}

/** Little-endian header bytes. */
private class Bytes {
    private val buf = ArrayList<Byte>(64)

    fun u16(v: Int) = apply { buf += (v and 0xFF).toByte(); buf += ((v shr 8) and 0xFF).toByte() }

    fun u32(v: Long) = apply { for (i in 0 until 4) buf += ((v shr (8 * i)) and 0xFF).toByte() }

    fun toByteArray() = buf.toByteArray()
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
