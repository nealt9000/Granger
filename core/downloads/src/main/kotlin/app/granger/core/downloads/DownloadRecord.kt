package app.granger.core.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.io.File

/** How a download is verified; archive.org offers sha1 or md5, the catalog uses sha256. */
enum class HashAlgorithm(val jcaName: String) { SHA256("SHA-256"), SHA1("SHA-1"), MD5("MD5") }

enum class ItemKind { PACK, MODEL, BOOK }

enum class DownloadState { QUEUED, RUNNING, FAILED, INSTALLED }

/**
 * One version of one catalog item: waiting, downloading, failed, or installed. Installed records are
 * the ledger Readiness checks files against, so they outlive the catalog entry that created them.
 */
@Entity(tableName = "downloads", primaryKeys = ["itemId", "hash"])
data class DownloadRecord(
    val itemId: String,
    /** The expected checksum, hex, computed with [hashAlgorithm]. */
    val hash: String,
    @ColumnInfo(defaultValue = "SHA256")
    val hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256,
    val kind: ItemKind,
    val title: String,
    val urls: List<String>,
    val sizeBytes: Long,
    /** Where the verified file ends up; it is written to `<filePath>.part` first. */
    val filePath: String,
    /** Packs and books (their release date); blank for models. */
    val snapshotDate: String,
    val wifiOnly: Boolean,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val error: String? = null,
    val hashFailures: Int = 0,
    /** When the file last passed a full checksum check (at install, or from Readiness). */
    val verifiedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

val DownloadRecord.file: File get() = File(filePath)
val DownloadRecord.partFile: File get() = File("$filePath.part")
val DownloadRecord.isActive: Boolean get() = state != DownloadState.INSTALLED
