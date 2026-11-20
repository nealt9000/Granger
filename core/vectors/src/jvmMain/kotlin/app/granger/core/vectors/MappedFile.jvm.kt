package app.granger.core.vectors

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

actual class MappedFile(private val channel: FileChannel, private val buf: ByteBuffer) : AutoCloseable {
    actual val size: Long get() = buf.capacity().toLong()
    actual fun byte(at: Long): Byte = buf.get(at.toInt())
    actual fun short(at: Long): Int = buf.getShort(at.toInt()).toInt() and 0xFFFF
    actual fun int(at: Long): Int = buf.getInt(at.toInt())
    actual fun long(at: Long): Long = buf.getLong(at.toInt())
    actual fun float(at: Long): Float = buf.getFloat(at.toInt())
    actual fun copy(at: Long, dst: ByteArray, offset: Int, length: Int) {
        buf.duplicate().apply { position(at.toInt()) }.get(dst, offset, length)
    }
    actual override fun close() = channel.close()
}

actual fun mapReadOnly(path: String): MappedFile? {
    val channel = try { RandomAccessFile(path, "r").channel } catch (_: IOException) { return null }
    return try {
        MappedFile(channel, channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN))
    } catch (_: IOException) {
        channel.close(); null
    }
}

actual fun writeFileAtomically(path: String, bytes: ByteArray) {
    val tmp = File("$path.part")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(File(path))) throw IOException("could not move ${tmp.name} into place")
}
