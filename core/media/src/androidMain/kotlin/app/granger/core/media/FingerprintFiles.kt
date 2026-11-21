package app.granger.core.media

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** [Fingerprint] from a channel: reads only the two ends, positionally; the channel's position is untouched. */
fun Fingerprint.of(channel: FileChannel): String {
    val size = channel.size()
    val head = readAt(channel, 0, minOf(size, Fingerprint.CHUNK_BYTES.toLong()).toInt())
    val tailLength = minOf(size, Fingerprint.CHUNK_BYTES.toLong()).toInt()
    val tail = readAt(channel, size - tailLength, tailLength)
    return of(size, head, tail)
}

private fun readAt(channel: FileChannel, at: Long, n: Int): ByteArray {
    val buf = ByteBuffer.allocate(n)
    while (buf.hasRemaining()) {
        if (channel.read(buf, at + buf.position()) < 0) throw IOException("File shorter than its size")
    }
    return buf.array()
}
