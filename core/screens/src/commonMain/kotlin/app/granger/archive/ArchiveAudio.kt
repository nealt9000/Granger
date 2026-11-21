package app.granger.archive

import app.granger.UiText
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.AudioOffer
import app.granger.core.downloads.DownloadRecord
import app.granger.sources.ItemStatus

/**
 * What archive.org's spoken-word and music views ask of the phone's media downloads (Plan I5 Task 2): Android's is the
 * app's `MediaDownloader` and `MediaDownloads` (`MediaArchiveAudio`); the iPhone's comes with music and audiobooks (I6).
 * An item is got as one download group, an album or an audiobook.
 */
interface ArchiveAudio {
    /** The download group of the archive.org item [identifier] (`media:archive-org:<identifier>`). */
    fun groupOf(identifier: String): String

    /** The item a group of [groupOf] is, or null for any other group. */
    fun identifierOf(groupId: String): String?

    /** Gets [item]'s [offer] as one group on [kind]'s shelf; [fileCount] hears its file count first. The notice to show, or null. */
    suspend fun get(item: ArchiveItem, offer: AudioOffer.Tracks, kind: ArchiveKind, client: ArchiveOrgClient, now: Long, fileCount: (Int) -> Unit): UiText?

    suspend fun cancel(groupId: String)

    suspend fun retry(groupId: String)

    suspend fun remove(groupId: String)

    /** The media root ("music", "audiobooks") a group's files lie under, or null when it has none yet. */
    suspend fun mediaRootOf(groupId: String): String?

    /** Whether [record] is a group's cover, which isn't counted as one of its files. */
    fun isCover(record: DownloadRecord): Boolean

    /** Where a group of [fileCount] files stands, from its records. */
    fun statusOf(groupId: String, fileCount: Int, records: List<DownloadRecord>): ItemStatus

    /** The file count of the item manifest beside the downloaded file at [filePath]; null when there's none. Blocking. */
    fun manifestFileCount(filePath: String): Int?

    companion object {
        /** The media source name archive.org's items are filed under (`MediaManifest.ARCHIVE_ORG`). */
        const val SOURCE = "archive-org"
    }
}
