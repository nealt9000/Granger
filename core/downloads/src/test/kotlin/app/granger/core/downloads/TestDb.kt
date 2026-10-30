package app.granger.core.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry

fun inMemoryDownloads(): DownloadDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DownloadDatabase::class.java)
        .allowMainThreadQueries()
        .build()

val SHA_1 = "1".repeat(64)
val SHA_2 = "2".repeat(64)

fun packEntry(id: String = "med", sha256: String = SHA_1, size: Long = 1_000, date: String = "2026-10-10", urls: List<String> = listOf("https://a.test/$id.zim")) =
    PackEntry(
        id = id, title = "Pack $id", category = "Medical", language = "eng", sizeBytes = size, sha256 = sha256,
        urls = urls, snapshotDate = date, license = "CC BY-SA 4.0", hasFulltextIndex = true,
        zimUuid = "u-$id", zimName = "n-$id",
    )

fun bookEntry(id: String = "gc-emma", sha256: String = SHA_1, size: Long = 500, date: String = "2026-10-17") =
    BookEntry(
        id = id, title = "Book $id", category = "Fiction", language = "en", sizeBytes = size, sha256 = sha256,
        urls = listOf("https://a.test/$id.epub"), releaseDate = date, license = "Public domain (US)",
        collection = "granger-classics",
    )

fun voiceEntry(id: String = "vits-piper-en_US-ljspeech-medium", sha256: String = SHA_2, size: Long = 67_456_670) = app.granger.core.catalog.VoiceEntry(
    id = id, title = "Granger voice (ljspeech)", engine = "piper", language = "en-US", sizeBytes = size, sha256 = sha256,
    url = "https://github.com/nealt9000/Granger/releases/download/voices-v1/$id.zip", license = "Public domain",
)

fun modelEntry(id: String = "e2b", sha256: String = SHA_2, size: Long = 2_000, url: String = "https://hf.test/$id") =
    ModelEntry(
        id = id, title = "Model $id", role = ModelRole.SMALL, fileName = "$id.litertlm", sizeBytes = size,
        minRamGb = 6, sha256 = sha256, url = url, license = "Apache-2.0", evalPassed = true,
    )

/**
 * Records what the manager asks of the queue. With [queueOf], stops reach that [DownloadQueue], as in the app;
 * [running] stands in for a file downloading now.
 */
class FakeScheduler(private val queueOf: () -> DownloadQueue? = { null }) : DownloadScheduler {
    /** Each ensure call: whether it asked for an unmetered network. */
    val ensured = mutableListOf<Boolean>()
    val stopped = mutableListOf<String>()
    var queueStops = 0
    var legacyForgotten = 0
    var running: String? = null
    override fun ensure(unmeteredOnly: Boolean) { ensured += unmeteredOnly }
    override suspend fun stopRun(itemId: String) { stopped += itemId; queueOf()?.stopRun(itemId) }
    override suspend fun stopQueue() { queueStops++; queueOf()?.stopRun(null) }
    override fun isRunning(itemId: String) = running == itemId || queueOf()?.isRunning(itemId) == true
    override fun forgetLegacyWork() { legacyForgotten++ }
}
