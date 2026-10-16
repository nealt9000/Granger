package app.granger.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DownloadRequest(val urls: List<String>, val sizeBytes: Long, val sha256: String)

sealed interface DownloadResult {
    data object Done : DownloadResult

    /** The bytes didn't match the expected sha256. The part file has been deleted. */
    data object HashMismatch : DownloadResult

    /** Writing on would leave less than the reserve free. The bytes so far are kept. */
    data class NoSpace(val neededBytes: Long) : DownloadResult

    /**
     * Every URL failed; [reason] is the last error. The bytes so far are kept for a resume.
     * [permanent] means every URL failed in a way retrying won't fix (gone, a different file, a bad address).
     */
    data class Failed(val reason: String, val permanent: Boolean = false) : DownloadResult
}

/**
 * Downloads one file into a `.part` file with HTTP Range resume, mirror fallback, a streaming
 * sha256 and a free-space check before every write. It never renames or registers anything.
 */
class FileDownloader(
    private val client: OkHttpClient,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(request: DownloadRequest, part: File, onProgress: (Long) -> Unit = {}): DownloadResult =
        withContext(io) {
            val dir = part.absoluteFile.parentFile
            if (dir == null || (!dir.isDirectory && !dir.mkdirs())) {
                return@withContext DownloadResult.Failed("Can't create the download folder")
            }
            if (part.length() > request.sizeBytes) part.delete()
            // Hash what an earlier run left, so the final check covers every byte of the file.
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = try {
                hashFile(part, digest)
            } catch (e: IOException) {
                return@withContext DownloadResult.Failed(e.message ?: "Can't read the partial download")
            }
            onProgress(offset)
            var lastError = "No download address"
            var permanent = true
            for (url in request.urls) {
                if (offset >= request.sizeBytes) break
                val attempt = fetch(url, request, part, dir, digest, offset, onProgress)
                ensureActive()
                offset = attempt.offset
                // A failed write can leave bytes the digest never saw; cut the file back to what it covers.
                if (part.length() != offset) {
                    try {
                        RandomAccessFile(part, "rw").use { it.setLength(offset) }
                    } catch (e: IOException) {
                        return@withContext DownloadResult.Failed(e.message ?: "Can't trim the partial download")
                    }
                }
                if (attempt.noSpace) return@withContext DownloadResult.NoSpace(request.sizeBytes - offset)
                attempt.error?.let { lastError = it }
                if (!attempt.permanent) permanent = false
            }
            if (offset < request.sizeBytes) return@withContext DownloadResult.Failed(lastError, permanent)
            if (digest.digest().toHex() != request.sha256.lowercase()) {
                part.delete()
                return@withContext DownloadResult.HashMismatch
            }
            DownloadResult.Done
        }

    private class Attempt(val offset: Long, val error: String? = null, val noSpace: Boolean = false, val permanent: Boolean = false)

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun fetch(
        url: String,
        request: DownloadRequest,
        part: File,
        dir: File,
        digest: MessageDigest,
        start: Long,
        onProgress: (Long) -> Unit,
    ): Attempt {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            return Attempt(start, "Bad address: $url", permanent = true)
        }
        if (start > 0) builder.header("Range", "bytes=$start-")
        val call = client.newCall(builder.build())
        // Cancelling the coroutine aborts a read that is blocked on the network.
        val cancelHook = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
        var offset = start
        try {
            call.execute().use { response ->
                val host = response.request.url.host
                val restart = when (response.code) {
                    206 -> {
                        if (start == 0L || !response.header("Content-Range").orEmpty().startsWith("bytes $start-")) {
                            return Attempt(offset, "$host sent the wrong part of the file", permanent = true)
                        }
                        false
                    }
                    200 -> true // no range support: start the file again
                    else -> return Attempt(offset, "$host answered ${response.code}", permanent = response.code.isPermanentStatus())
                }
                val body = response.body
                val expected = if (restart) request.sizeBytes else request.sizeBytes - start
                if (body.contentLength() != -1L && body.contentLength() != expected) {
                    return Attempt(offset, "$host has a different file", permanent = true)
                }
                if (restart && start > 0) {
                    digest.reset()
                    offset = 0
                }
                FileOutputStream(part, !restart).use { out ->
                    try {
                        val input = body.byteStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var reported = offset
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (offset + n > request.sizeBytes) return Attempt(offset, "$host sent too many bytes", permanent = true)
                            if (freeBytes(dir) - n < reserveBytes) return Attempt(offset, noSpace = true)
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                // A volume that filled up anyway (another app wrote to it) is out of space, not a network error.
                                if (freeBytes(dir) < reserveBytes + BUFFER_SIZE) return Attempt(offset, noSpace = true)
                                throw e
                            }
                            digest.update(buffer, 0, n)
                            offset += n
                            if (offset - reported >= PROGRESS_STEP || offset == request.sizeBytes) {
                                onProgress(offset)
                                reported = offset
                            }
                        }
                    } finally {
                        out.flush()
                        out.fd.sync()
                    }
                }
                return if (offset < request.sizeBytes) Attempt(offset, "$host closed the connection early") else Attempt(offset)
            }
        } catch (e: IOException) {
            return Attempt(offset, e.message ?: "Network error")
        } finally {
            cancelHook.dispose()
        }
    }

    /** 4xx means this URL won't work however often we ask, except a timeout (408) or a rate limit (429). */
    private fun Int.isPermanentStatus() = this in 400..499 && this != 408 && this != 429

    companion object {
        /** Never fill a volume past this, whatever the queue check allowed. */
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
        private const val PROGRESS_STEP = 256L * 1024
    }
}
