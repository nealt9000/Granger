package app.granger.core.vectors

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.asCoroutineDispatcher

actual class MappedFile(private val channel: FileChannel, private val buf: ByteBuffer) : AutoCloseable {
    actual val size: Long get() = buf.capacity().toLong()
    actual fun byte(at: Long): Byte = buf.get(at.toInt())
    actual fun copy(at: Long, dst: ByteArray, offset: Int, length: Int) {
        buf.duplicate().apply { position(at.toInt()) }.get(dst, offset, length)
    }
    actual override fun close() = channel.close()
}

actual fun mapReadOnly(path: String): MappedFile? {
    val channel = try { RandomAccessFile(path, "r").channel } catch (_: IOException) { return null }
    return try {
        MappedFile(channel, channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
    } catch (_: IOException) {
        channel.close(); null
    }
}

actual fun writeFileAtomically(path: String, bytes: ByteArray) {
    val tmp = File("$path.part")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(File(path))) throw IOException("could not move ${tmp.name} into place")
}

/** The Android side keeps its File-based calls. */
val VectorFile.file: File get() = File(path)
fun VectorFile.Companion.open(file: File): VectorFile? = open(file.path)

fun VectorFile.Companion.write(
    file: File,
    vectors: List<FloatArray>,
    dims: Int,
    hashes: List<String>,
    keys: List<ChunkKey>,
    chunkerVersion: Int,
    embedderSha256: String,
    sourceSha256: String,
) = write(file.path, vectors, dims, hashes, keys, chunkerVersion, embedderSha256, sourceSha256)

/** The Android callers' blocking form, over their thread pool. */
fun VectorSearch.top(query: FloatArray, files: List<VectorFile>, k: Int, pool: java.util.concurrent.ExecutorService): List<VectorHit> =
    kotlinx.coroutines.runBlocking { top(query, files, k, pool.asCoroutineDispatcher()) }
