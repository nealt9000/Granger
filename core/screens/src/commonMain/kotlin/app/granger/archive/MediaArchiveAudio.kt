package app.granger.archive

import app.granger.UiText
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.AudioOffer
import app.granger.core.downloads.DownloadRecord
import app.granger.core.media.MediaDisk
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.media.MediaDownloads
import app.granger.media.MediaItemDownloader
import app.granger.sources.ItemStatus

/**
 * archive.org's audio views on the media downloads (the shared model's [ArchiveAudio]). Shared since Plan I6 Task 3, so
 * the iPhone's `archiveOrg()` can pass one; Android passes its `MediaDownloader`.
 */
class MediaArchiveAudio(private val media: MediaItemDownloader) : ArchiveAudio {
    override fun groupOf(identifier: String) = MediaDownloads.groupId(MediaManifest.ARCHIVE_ORG, identifier)

    override fun identifierOf(groupId: String) = MediaDownloads.refOf(groupId, MediaManifest.ARCHIVE_ORG)

    override suspend fun get(
        item: ArchiveItem, offer: AudioOffer.Tracks, kind: ArchiveKind, client: ArchiveOrgClient, now: Long, fileCount: (Int) -> Unit,
    ): UiText? {
        val manifest = MediaDownloads.albumManifest(item, offer, kind, client, now)
        fileCount(manifest.files.size)
        return media.get(manifest)
    }

    override suspend fun cancel(groupId: String) = media.cancel(groupId)

    override suspend fun retry(groupId: String) {
        media.retry(groupId)
    }

    override suspend fun remove(groupId: String) = media.remove(groupId)

    override suspend fun mediaRootOf(groupId: String) = media.mediaRootOf(groupId)

    override fun isCover(record: DownloadRecord) = MediaDownloads.isCover(record)

    override fun statusOf(groupId: String, fileCount: Int, records: List<DownloadRecord>): ItemStatus =
        MediaDownloads.statusOf(groupId, fileCount, records)

    override fun manifestFileCount(filePath: String): Int? = MediaDisk.parent(filePath)?.let(MediaManifests::read)?.files?.size
}
