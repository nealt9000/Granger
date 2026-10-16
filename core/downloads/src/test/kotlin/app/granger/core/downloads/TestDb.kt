package app.granger.core.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

fun modelEntry(id: String = "e2b", sha256: String = SHA_2, size: Long = 2_000, url: String = "https://hf.test/$id") =
    ModelEntry(
        id = id, title = "Model $id", role = ModelRole.SMALL, fileName = "$id.litertlm", sizeBytes = size,
        minRamGb = 6, sha256 = sha256, url = url, license = "Apache-2.0", evalPassed = true,
    )

class FakeScheduler : DownloadScheduler {
    val scheduled = mutableListOf<Pair<DownloadRecord, Boolean>>()
    val cancelled = mutableListOf<String>()
    override fun schedule(record: DownloadRecord, replace: Boolean) { scheduled += record to replace }
    override fun cancel(itemId: String) { cancelled += itemId }
}
