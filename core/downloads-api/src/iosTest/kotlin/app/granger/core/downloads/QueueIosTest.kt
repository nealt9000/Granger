package app.granger.core.downloads

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared queue on the iPhone (Plan I4 Task 1): Room on the bundled SQLite, the manager, the queue and the runner. */
class QueueIosTest {
    private val root = Path(SystemTemporaryDirectory, "queue-${Random.nextLong()}").also { SystemFileSystem.createDirectories(it) }.toString()

    /** Writes the whole file at once, as a URLSession transfer hands one over. */
    private class FakeTransfer(private val text: String) : FileTransfer {
        val asked = mutableListOf<String>()
        override suspend fun download(request: DownloadRequest, partPath: String, onProgress: (Long) -> Unit): DownloadResult {
            asked += request.urls.first()
            Files.parent(partPath)?.let { SystemFileSystem.createDirectories(Path(it)) }
            SystemFileSystem.sink(Path(partPath)).buffered().use { it.writeString(text) }
            onProgress(text.length.toLong())
            return DownloadResult.Done
        }
    }

    private class Scheduler : DownloadScheduler {
        var ensured = 0
        override fun ensure(unmeteredOnly: Boolean, offline: Boolean) { ensured++ }
        override suspend fun stopRun(itemId: String) {}
        override suspend fun stopQueue() {}
        override fun isRunning(itemId: String) = false
    }

    private fun spec(id: String, size: Long) = DownloadSpec(
        id, ItemKind.PACK, id, listOf("https://example.org/$id.zim"), size, "unverified-$id", HashAlgorithm.SHA256,
        "packs/$id.zim", "2026-11",
    )

    @Test
    fun queuesRunsAndInstallsInOrder() = runBlocking {
        val db = openDownloadDatabase(Files.join(root, "downloads.db"))
        val dao = db.downloadDao()
        val scheduler = Scheduler()
        val manager = DownloadManager(dao, scheduler)
        val installed = mutableListOf<String>()
        val transfer = FakeTransfer("hello")
        val queue = DownloadQueue(dao, DownloadRunner(dao, transfer, { installed += it.itemId }), MemoryQueuePrefs(), unmetered = { true })

        assertEquals(EnqueueResult.Queued, manager.enqueue(spec("big", 5), root, wifiOnly = true))
        assertEquals(EnqueueResult.Queued, manager.enqueueGroup("g", listOf(spec("a", 5).copy(groupId = "g")), root, wifiOnly = true))
        assertTrue(scheduler.ensured > 0)
        assertEquals(EnqueueResult.AlreadyActive, manager.enqueue(spec("big", 5), root, wifiOnly = true))

        assertEquals(QueueRest.Idle, queue.drain())
        assertEquals(listOf("big", "a"), installed)
        val records = manager.records.first()
        assertTrue(records.all { it.state == DownloadState.INSTALLED })
        assertTrue(Files.isFile(Files.join(root, "packs/big.zim")))
        assertEquals(5L, Files.size(Files.join(root, "packs/a.zim")))
        assertTrue(!Files.exists(Files.join(root, "packs/a.zim.part")))

        manager.remove("big")
        assertTrue(!Files.exists(Files.join(root, "packs/big.zim")))
        db.close()
    }

    @Test
    fun noSpaceIsSaidUpFront() = runBlocking {
        val db = openDownloadDatabase(Files.join(root, "d2.db"))
        val manager = DownloadManager(db.downloadDao(), Scheduler(), freeBytes = { 100L })
        val r = manager.enqueue(spec("huge", 10_000_000_000), root, wifiOnly = false)
        assertTrue(r is EnqueueResult.NoSpace)
        assertTrue(freeBytesAt(root) > 0)
        db.close()
    }
}
