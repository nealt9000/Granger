package app.granger.core.media

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * A phone file's identity: survives moves and renames, not edits to its ends (where tags live). sha1 over "$size:",
 * the first 64 KiB and the last 64 KiB (they overlap in a file under 128 KiB), first 16 hex chars.
 */
object Fingerprint {
    const val CHUNK_BYTES = 64 * 1024

    fun of(sizeBytes: Long, head: ByteArray, tail: ByteArray): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update("$sizeBytes:".toByteArray(Charsets.US_ASCII))
        sha1.update(head)
        sha1.update(tail)
        return sha1.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Reads only the two ends, positionally; the channel's position is untouched. */
    fun of(channel: FileChannel): String {
        val size = channel.size()
        val head = read(channel, 0, minOf(size, CHUNK_BYTES.toLong()).toInt())
        val tailLength = minOf(size, CHUNK_BYTES.toLong()).toInt()
        val tail = read(channel, size - tailLength, tailLength)
        return of(size, head, tail)
    }

    private fun read(channel: FileChannel, at: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) {
            if (channel.read(buf, at + buf.position()) < 0) throw IOException("File shorter than its size")
        }
        return buf.array()
    }
}
