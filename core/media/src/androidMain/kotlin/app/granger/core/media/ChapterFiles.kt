package app.granger.core.media

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// Android's ways into the shared ChapterReader (Plan I6 Task 1), the byte sources it read before it was shared.

/** The chapters of [file], opened read-only by the caller (who closes it). */
fun ChapterReader.read(file: RandomAccessFile): List<Chapter> = read(RandomAccessBytes(file))

/**
 * The same, from a channel (e.g. `FileInputStream(pfd.fileDescriptor).channel` for a file opened read-only through
 * a content resolver). Reads are positional, so the channel's position is neither used nor changed.
 */
fun ChapterReader.read(channel: FileChannel): List<Chapter> = read(ChannelBytes(channel))

private class RandomAccessBytes(private val file: RandomAccessFile) : ChapterBytes {
    override val length = file.length()
    override fun read(pos: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        file.seek(pos)
        file.readFully(out)
        return out
    }
}

private class ChannelBytes(private val channel: FileChannel) : ChapterBytes {
    override val length = channel.size()
    override fun read(pos: Long, n: Int): ByteArray {
        val out = ByteBuffer.allocate(n)
        while (out.hasRemaining()) {
            if (channel.read(out, pos + out.position()) < 0) break
        }
        return if (out.hasRemaining()) out.array().copyOf(out.position()) else out.array()
    }
}
