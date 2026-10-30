package app.granger.core.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.io.File

/** How a download is verified; archive.org offers sha1 or md5, the catalog uses sha256. */
enum class HashAlgorithm(val jcaName: String) { SHA256("SHA-256"), SHA1("SHA-1"), MD5("MD5") }

/**
 * [AUDIO]: one file of a LibriVox or archive.org audio item, downloaded as part of a group. [VOICE]: a read-aloud voice's
 * zip, unpacked after verification. [VECTORS]: a pack's or book's search vectors (Plan R1), which follow it; see
 * [VectorDownloads]. Stored by name, so a new kind needs no migration.
 */
enum class ItemKind { PACK, MODEL, BOOK, AUDIO, VOICE, VECTORS }

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
    /** The download group (one book or album) this file belongs to; null for packs, models and single books. */
    @ColumnInfo(defaultValue = "NULL")
    val groupId: String? = null,
    val wifiOnly: Boolean,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val error: String? = null,
    val hashFailures: Int = 0,
    /** When the file last passed a full checksum check (at install, or from Readiness). */
    val verifiedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Its place in the one download queue, lowest first (Plan DL Decision 2); ties go to the older record. */
    @ColumnInfo(defaultValue = "0")
    val queueOrder: Long = 0,
    /** Tries that failed in a way worth retrying; at [MAX_ATTEMPTS] the record is FAILED. */
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,
    /** When a record that failed for now may run again; null means as soon as its turn comes. */
    val retryAt: Long? = null,
)

/** Tries per download before it is FAILED with its last error (Plan DL Decision 4). */
const val MAX_ATTEMPTS = 10

val DownloadRecord.file: File get() = File(filePath)
val DownloadRecord.partFile: File get() = File("$filePath.part")
val DownloadRecord.isActive: Boolean get() = state != DownloadState.INSTALLED

/**
 * A file every address answered 404 or 410 for (Plan DL Decision 10): retrying won't bring it back, so it is FAILED at
 * once and offers Remove. The record's error is [message], which every screen can show as it is; [isGone] reads it back.
 */
object GoneFile {
    const val KIWIX = "Kiwix no longer offers this file"
    const val OTHER = "This file is no longer offered"

    fun message(urls: List<String>): String = if (urls.any { it.isKiwix() }) KIWIX else OTHER

    private fun String.isKiwix() = substringAfter("://").substringBefore('/').let { it == "kiwix.org" || it.endsWith(".kiwix.org") }
}

val DownloadRecord.isGone: Boolean
    get() = state == DownloadState.FAILED && (error == GoneFile.KIWIX || error == GoneFile.OTHER)
