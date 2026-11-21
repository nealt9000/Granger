@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.granger.core.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.darwin.NSObject

/**
 * The iPhone's [FileTransfer] (Plan I4 Task 6, builder's call 3): one URLSession background session, so a download
 * carries on while the app is in the background, and one the system ends (the app force-quit) resumes from its resume
 * data on the next start. Each task is tagged with its record's part path; whatever finishes while nothing waits for it
 * (the app was gone) is moved to that path and picked up by the next [download] of it. The whole file is checked
 * against its sha256 before it counts as done; [DownloadRunner] then moves it into place.
 *
 * Wi-Fi only sets `allowsExpensiveNetworkAccess = false` on the request: such a task waits for Wi-Fi rather than using
 * mobile data. Cancelling [download] (Pause all, Cancel, a small file going ahead) cancels the task, keeping its resume
 * data in `<part>.resume`.
 */
class UrlSessionTransfer(
    identifier: String = "app.granger.downloads",
    /** Called when the system has delivered every event of the background session (the app's completion handler). */
    private val eventsDone: () -> Unit = {},
) : FileTransfer {
    private val lock = QueueLock()
    private val waiters = HashMap<String, CancellableContinuation<Outcome>>()
    private val progress = HashMap<String, (Long) -> Unit>()
    private val sizes = HashMap<String, Long>()

    private sealed interface Outcome {
        /** The file is at the part path (moved there by the delegate). */
        data object Arrived : Outcome
        data class Http(val status: Int) : Outcome
        data class Error(val reason: String, val resumable: Boolean) : Outcome
    }

    private val delegate = Delegate()

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
        }
        NSURLSession.sessionWithConfiguration(config, delegate, NSOperationQueue().apply { maxConcurrentOperationCount = 1 })
    }

    /** Creates the session early, so tasks the system finished while the app was gone deliver their events. */
    fun connect() { session }

    override suspend fun download(request: DownloadRequest, partPath: String, onProgress: (Long) -> Unit): DownloadResult {
        // A file that arrived while nothing waited for it (or a previous run's) is checked as it is.
        if (Files.size(partPath) == request.sizeBytes) return verify(request, partPath)
        if (Files.size(partPath) > request.sizeBytes) Files.delete(partPath)
        Files.parent(partPath)?.let { runCatching { SystemFileSystem.createDirectories(Path(it)) } }
        onProgress(0)
        var lastError = "No download address"
        var permanent = true
        var gone = request.urls.isNotEmpty()
        for ((i, url) in request.urls.withIndex()) {
            val outcome = run(request, partPath, url, resume = i == 0, onProgress)
            when (outcome) {
                Outcome.Arrived -> return verify(request, partPath)
                is Outcome.Http -> {
                    val host = NSURL.URLWithString(url)?.host ?: "The server"
                    lastError = "$host answered ${outcome.status}"
                    if (!(outcome.status in 400..499 && outcome.status != 408 && outcome.status != 429)) permanent = false
                    if (outcome.status != 404 && outcome.status != 410) gone = false
                }
                is Outcome.Error -> {
                    lastError = outcome.reason
                    permanent = false
                    gone = false
                    // A transfer that can resume tries this address again next time rather than starting another.
                    if (outcome.resumable) break
                }
            }
        }
        return DownloadResult.Failed(lastError, permanent, gone && permanent)
    }

    private suspend fun run(request: DownloadRequest, partPath: String, url: String, resume: Boolean, onProgress: (Long) -> Unit): Outcome {
        val running = runningTask(partPath)
        return suspendCancellableCoroutine { cont ->
            lock.with {
                waiters[partPath] = cont
                progress[partPath] = onProgress
                sizes[partPath] = request.sizeBytes
            }
            val task = running ?: start(request, partPath, url, resume) ?: run {
                lock.with { waiters.remove(partPath); progress.remove(partPath) }
                cont.resumeWith(Result.success(Outcome.Error("Bad address: $url", resumable = false)))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                lock.with { waiters.remove(partPath); progress.remove(partPath) }
                (task as? NSURLSessionDownloadTask)?.cancelByProducingResumeData { data -> saveResume(partPath, data) } ?: task.cancel()
            }
        }
    }

    private fun start(request: DownloadRequest, partPath: String, url: String, resume: Boolean): NSURLSessionTask? {
        val data = if (resume) NSData.dataWithContentsOfFile(resumePath(partPath)) else null
        Files.delete(resumePath(partPath))
        val task = if (data != null) {
            session.downloadTaskWithResumeData(data)
        } else {
            val nsUrl = NSURL.URLWithString(url) ?: return null
            val req = NSMutableURLRequest(uRL = nsUrl).apply {
                setAllowsExpensiveNetworkAccess(!request.wifiOnly)
                setAllowsConstrainedNetworkAccess(!request.wifiOnly)
            }
            session.downloadTaskWithRequest(req)
        }
        task.taskDescription = partPath
        task.resume()
        return task
    }

    /** A task already under way for [partPath] (started before the app was suspended or quit), if the session has one. */
    private suspend fun runningTask(partPath: String): NSURLSessionTask? = suspendCancellableCoroutine { cont ->
        session.getAllTasksWithCompletionHandler { tasks ->
            val found = tasks?.filterIsInstance<NSURLSessionTask>()?.firstOrNull { it.taskDescription == partPath && it.state != platform.Foundation.NSURLSessionTaskStateCompleted }
            cont.resumeWith(Result.success(found))
        }
    }

    private fun resumePath(partPath: String) = "$partPath.resume"

    private fun saveResume(partPath: String, data: NSData?) {
        if (data != null) data.writeToFile(resumePath(partPath), atomically = true)
    }

    /** The whole file against its size and sha256; a mismatch deletes it, as Android's FileDownloader does. */
    private suspend fun verify(request: DownloadRequest, partPath: String): DownloadResult = withContext(Dispatchers.IO) {
        if (Files.size(partPath) != request.sizeBytes) return@withContext DownloadResult.Failed("The file arrived the wrong size")
        if (request.hash.startsWith(UNVERIFIED_PREFIX)) return@withContext DownloadResult.Done
        if (request.algorithm != HashAlgorithm.SHA256) return@withContext DownloadResult.Failed("Can't check this file's checksum", permanent = true)
        val digest = sha256Of(partPath) ?: return@withContext DownloadResult.Failed("Can't read the downloaded file")
        if (digest == request.hash.lowercase()) DownloadResult.Done else {
            Files.delete(partPath)
            DownloadResult.HashMismatch
        }
    }

    private fun finish(partPath: String, outcome: Outcome) {
        val cont = lock.with { progress.remove(partPath); sizes.remove(partPath); waiters.remove(partPath) }
        cont?.resumeWith(Result.success(outcome))
    }

    private inner class Delegate : NSObject(), NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
            val partPath = downloadTask.taskDescription ?: return
            val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
            val from = didFinishDownloadingToURL.path ?: return
            if (status !in 200..299) {
                NSFileManager.defaultManager.removeItemAtPath(from, null)
                delivered[partPath] = Outcome.Http(status)
                return
            }
            // The system deletes the file when this returns: it's moved now, to the part path.
            NSFileManager.defaultManager.removeItemAtPath(partPath, null)
            val moved = NSFileManager.defaultManager.moveItemAtPath(from, partPath, null)
            delivered[partPath] = if (moved) Outcome.Arrived else Outcome.Error("Couldn't save the finished file", resumable = false)
        }

        override fun URLSession(
            session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didWriteData: Long, totalBytesWritten: Long, totalBytesExpectedToWrite: Long,
        ) {
            val partPath = downloadTask.taskDescription ?: return
            val (report, size) = lock.with { progress[partPath] to (sizes[partPath] ?: 0L) }
            // A resumed task counts only what it fetches now: what's done is the file's size less what's still to come.
            val done = if (totalBytesExpectedToWrite > 0 && size > 0) size - (totalBytesExpectedToWrite - totalBytesWritten) else totalBytesWritten
            report?.invoke(done.coerceAtLeast(0))
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val partPath = task.taskDescription ?: return
            val arrived = delivered.remove(partPath)
            val outcome = when {
                didCompleteWithError == null -> arrived ?: Outcome.Error("The download ended without a file", resumable = false)
                else -> {
                    val data = didCompleteWithError.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData
                    saveResume(partPath, data)
                    Outcome.Error(didCompleteWithError.localizedDescription, resumable = data != null)
                }
            }
            finish(partPath, outcome)
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) = eventsDone()
    }

    /** What a finished task left, until its completion arrives (both on the session's one-at-a-time delegate queue). */
    private val delivered = HashMap<String, Outcome>()
}

/** A file's sha256 as lower-case hex, read in 1 MB pieces; null when it can't be read. */
@OptIn(ExperimentalForeignApi::class)
internal fun sha256Of(path: String): String? = runCatching {
    memScoped {
        val ctx = alloc<CC_SHA256_CTX>()
        CC_SHA256_Init(ctx.ptr)
        SystemFileSystem.source(Path(path)).buffered().use { source ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = source.readAtMostTo(buffer, 0, buffer.size)
                if (n <= 0) break
                buffer.usePinned { CC_SHA256_Update(ctx.ptr, it.addressOf(0), n.convert()) }
            }
        }
        val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { CC_SHA256_Final(it.addressOf(0), ctx.ptr) }
        out.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}.getOrNull()
