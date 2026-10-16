package app.granger.core.downloads

import androidx.room.Entity
import java.io.File

enum class ItemKind { PACK, MODEL }

enum class DownloadState { QUEUED, RUNNING, FAILED, INSTALLED }

/**
 * One version of one catalog item: waiting, downloading, failed, or installed. Installed records are
 * the ledger Readiness checks files against, so they outlive the catalog entry that created them.
 */
@Entity(tableName = "downloads", primaryKeys = ["itemId", "sha256"])
data class DownloadRecord(
    val itemId: String,
    val sha256: String,
    val kind: ItemKind,
    val title: String,
    val urls: List<String>,
    val sizeBytes: Long,
    /** Where the verified file ends up; it is written to `<filePath>.part` first. */
    val filePath: String,
    /** Packs only; blank for models. */
    val snapshotDate: String,
    val wifiOnly: Boolean,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val error: String? = null,
    val hashFailures: Int = 0,
    /** When the file last passed a full sha256 check (at install, or from Readiness). */
    val verifiedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

val DownloadRecord.file: File get() = File(filePath)
val DownloadRecord.partFile: File get() = File("$filePath.part")
val DownloadRecord.isActive: Boolean get() = state != DownloadState.INSTALLED
