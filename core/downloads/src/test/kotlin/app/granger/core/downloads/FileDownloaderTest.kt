package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val content = Random(7).nextBytes(100_000)
    private val a = "https://a.test/f.zim"
    private val b = "https://b.test/f.zim"
    private var freeBytes: (File) -> Long = { Long.MAX_VALUE / 2 }
    private val downloader by lazy { FileDownloader(server.client(), freeBytes = { freeBytes(it) }, reserveBytes = 1_000) }
    private val part by lazy { File(tmp.root, "packs/f.zim.part") }

    private fun request(vararg urls: String) = DownloadRequest(urls.toList(), content.size.toLong(), sha256(content))

    private fun partWith(bytes: ByteArray) {
        part.parentFile!!.mkdirs()
        part.writeBytes(bytes)
    }

    @Test fun downloadsAndVerifies() = runTest {
        server.files[a] = content
        val progress = mutableListOf<Long>()
        val result = downloader.download(request(a), part) { progress += it }
        assertThat(result).isEqualTo(DownloadResult.Done)
        assertThat(part.readBytes()).isEqualTo(content)
        assertThat(progress.first()).isEqualTo(0L)
        assertThat(progress.last()).isEqualTo(content.size.toLong())
        assertThat(server.rangesRequested(a)).containsExactly(null)
    }

    @Test fun resumesFromPartFile() = runTest {
        server.files[a] = content
        partWith(content.copyOf(40_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a)).containsExactly("bytes=40000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun serverIgnoringRangeRestartsTheFile() = runTest {
        server.files[a] = content
        server.ignoreRange += a
        partWith(content.copyOf(40_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(part.length()).isEqualTo(content.size.toLong())
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun partLargerThanTheFileStartsOver() = runTest {
        server.files[a] = content
        partWith(Random(9).nextBytes(150_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a)).containsExactly(null)
    }

    @Test fun verifiesWithSha1() = runTest {
        server.files[a] = content
        val result = downloader.download(DownloadRequest(listOf(a), content.size.toLong(), sha1(content), HashAlgorithm.SHA1), part)
        assertThat(result).isEqualTo(DownloadResult.Done)
    }

    @Test fun md5MismatchDeletesThePart() = runTest {
        server.files[a] = content
        val result = downloader.download(DownloadRequest(listOf(a), content.size.toLong(), "0".repeat(32), HashAlgorithm.MD5), part)
        assertThat(result).isEqualTo(DownloadResult.HashMismatch)
        assertThat(part.exists()).isFalse()
    }

    @Test fun resumedSha1DownloadHashesTheEarlierBytes() = runTest {
        server.files[a] = content
        partWith(content.copyOf(40_000))
        val result = downloader.download(DownloadRequest(listOf(a), content.size.toLong(), sha1(content), HashAlgorithm.SHA1), part)
        assertThat(result).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a)).containsExactly("bytes=40000-")
    }

    @Test fun completePartNeedsNoNetwork() = runTest {
        partWith(content)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.requests).isEmpty()
    }

    @Test fun corruptCompletePartIsAMismatch() = runTest {
        partWith(Random(3).nextBytes(content.size))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.HashMismatch)
        assertThat(part.exists()).isFalse()
    }

    @Test fun connectionDropContinuesOnNextMirror() = runTest {
        server.files[a] = content
        server.files[b] = content
        server.failAfter[a] = 30_000
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(b)).containsExactly("bytes=30000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun failedAttemptKeepsBytesForNextRun() = runTest {
        server.files[a] = content
        server.failAfter[a] = 30_000
        val first = downloader.download(request(a), part)
        assertThat(first).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat(part.length()).isEqualTo(30_000L)

        server.failAfter.remove(a)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a).last()).isEqualTo("bytes=30000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun notFoundTriesTheNextMirror() = runTest {
        server.files[b] = content
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
    }

    @Test fun mirrorWithADifferentFileIsSkipped() = runTest {
        server.files[a] = Random(5).nextBytes(5_000)
        server.files[b] = content
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun wrongContentIsAMismatchAndDeleted() = runTest {
        server.files[a] = Random(8).nextBytes(content.size)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.HashMismatch)
        assertThat(part.exists()).isFalse()
    }

    @Test fun stopsBeforeTheDiskFills() = runTest {
        server.files[a] = content
        freeBytes = { 21_000 - part.length() }
        val result = downloader.download(request(a), part)
        assertThat(result).isInstanceOf(DownloadResult.NoSpace::class.java)
        assertThat((result as DownloadResult.NoSpace).neededBytes).isEqualTo(content.size - part.length())
        assertThat(part.length()).isAtMost(20_000L)
    }

    @Test fun everyMirrorFailingReportsTheLastError() = runTest {
        server.status[a] = 503
        val result = downloader.download(request(a), part)
        assertThat((result as DownloadResult.Failed).reason).contains("503")
    }

    @Test fun everyMirrorMissingTheFileIsPermanent() = runTest {
        val result = downloader.download(request(a, b), part)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat((result as DownloadResult.Failed).permanent).isTrue()
        assertThat(result.reason).contains("404")
    }

    @Test fun aFileEveryMirrorAnswers404Or410ForIsGone() = runTest {
        server.status[a] = 404
        server.status[b] = 410
        val result = downloader.download(request(a, b), part) as DownloadResult.Failed
        assertThat(result.gone).isTrue()
        assertThat(result.permanent).isTrue()
    }

    @Test fun oneMirrorStillServingIsNotGone() = runTest {
        server.status[a] = 404
        server.status[b] = 403
        assertThat((downloader.download(request(a, b), part) as DownloadResult.Failed).gone).isFalse()
        server.status[b] = 503
        assertThat((downloader.download(request(a, b), part) as DownloadResult.Failed).gone).isFalse()
    }

    @Test fun aMirrorWithADifferentFileIsPermanent() = runTest {
        server.files[a] = Random(5).nextBytes(5_000)
        val result = downloader.download(request(a), part) as DownloadResult.Failed
        assertThat(result.permanent).isTrue()
    }

    @Test fun oneMissingAndOneDroppedIsNotPermanent() = runTest {
        server.files[b] = content
        server.failAfter[b] = 1_000
        val result = downloader.download(request(a, b), part) as DownloadResult.Failed
        assertThat(result.permanent).isFalse()
    }

    @Test fun serverErrorsAndRateLimitsAreNotPermanent() = runTest {
        server.status[a] = 404
        server.status[b] = 429
        assertThat((downloader.download(request(a, b), part) as DownloadResult.Failed).permanent).isFalse()
        server.status[b] = 503
        assertThat((downloader.download(request(a, b), part) as DownloadResult.Failed).permanent).isFalse()
    }

    @Test fun aBadAddressIsPermanent() = runTest {
        val result = downloader.download(request("not a url"), part) as DownloadResult.Failed
        assertThat(result.permanent).isTrue()
        assertThat(result.reason).contains("Bad address")
    }

    @Test fun cancellingDuringAStalledReadReturnsPromptly() {
        server.files[a] = content
        server.hang += a
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch { downloader.download(request(a), part) }
            withTimeout(5_000) { while (server.requests.isEmpty()) delay(10) }
            delay(100)
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertThat(job.isCancelled).isTrue()
        }
    }

    @Test fun unreadablePartFileIsAFailureNotACrash() = runTest {
        partWith(content.copyOf(10_000))
        part.setReadable(false)
        assumeTrue(!part.canRead())
        val result = downloader.download(request(a), part)
        assertThat(result).isInstanceOf(DownloadResult.Failed::class.java)
    }
}
