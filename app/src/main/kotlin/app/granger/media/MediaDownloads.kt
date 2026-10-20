package app.granger.media

import app.granger.core.archive.ArchiveFile
import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.LibriVoxClient
import app.granger.core.archive.LibriVoxOffer
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadSpec
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import app.granger.core.library.Albums
import app.granger.core.library.Audiobook
import app.granger.core.library.MediaOrigin
import app.granger.core.media.ManifestFile
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import app.granger.sources.ItemStatus
import java.io.File

/**
 * Media downloads as download groups: one group per book or album, `media:<source>:<ref>`, its files `<groupId>/<NNN>`
 * (the cover is always `000`, the audio files `001` on, so an id never shifts). None starts with `archive-org:`, so
 * Plan 7's archive book code never sees them.
 */
object MediaDownloads {
    const val PREFIX = "media:"
    const val COVER_NAME = "cover.jpg"

    fun groupId(source: String, ref: String): String = "$PREFIX$source:$ref"

    /** The ref of [groupId] when it's a media group of [source], else null. */
    fun refOf(groupId: String, source: String): String? =
        groupId.takeIf { it.startsWith("$PREFIX$source:") }?.removePrefix("$PREFIX$source:")?.takeIf { it.isNotEmpty() && '/' !in it }

    fun fileItemId(groupId: String, ordinal: Int): String = "$groupId/%03d".format(ordinal)

    fun groupId(m: MediaManifest): String = groupId(m.source, m.ref)

    /** An archive.org audio item as an album (got from Music) or an audiobook (got from Audiobooks). */
    fun albumManifest(item: ArchiveItem, offer: AudioOffer.Tracks, kind: ArchiveKind, client: ArchiveOrgClient, now: Long): MediaManifest {
        val identifier = item.identifier
        require(ArchiveQuery.isValidIdentifier(identifier)) { "Bad archive.org identifier" }
        val shelf = when (kind) {
            ArchiveKind.MUSIC -> MediaManifest.ALBUM
            ArchiveKind.SPOKEN_WORD -> MediaManifest.AUDIOBOOK
            ArchiveKind.TEXTS -> throw IllegalArgumentException("Texts aren't media")
        }
        val files = offer.files.mapIndexed { i, f ->
            manifestFile(MediaManifests.localName(i, f.title, f.name), f, client.downloadUrl(identifier, f.name))
                .copy(title = f.title.trim(), trackNo = f.track, durationMs = f.lengthMs)
        }
        return MediaManifest(
            source = MediaManifest.ARCHIVE_ORG,
            kind = shelf,
            ref = identifier,
            archiveIdentifier = identifier,
            pageUrl = client.detailsUrl(identifier),
            title = item.title,
            // archive.org's creators come joined by ", "; splitting again would break "Holst, Gustav".
            creators = listOfNotNull(item.creator.trim().takeIf { it.isNotEmpty() }),
            description = item.description,
            language = item.language,
            date = item.date,
            totalTimeMs = files.sumOf { it.durationMs ?: 0L },
            cover = offer.cover?.let { manifestFile(COVER_NAME, it, client.downloadUrl(identifier, it.name)) },
            files = files,
            gotAt = now,
        )
    }

    /** A LibriVox book: its sections in order, each with its archive.org file, readers and section id. */
    fun bookManifest(offer: LibriVoxOffer.Files, client: ArchiveOrgClient, now: Long): MediaManifest {
        val book = offer.book
        require(LibriVoxClient.isValidId(book.id)) { "Bad LibriVox id" }
        val identifier = offer.item.identifier
        require(ArchiveQuery.isValidIdentifier(identifier)) { "Bad archive.org identifier" }
        val files = offer.files.mapIndexed { i, (section, f) ->
            manifestFile(MediaManifests.localName(i, section.title, f.name), f, client.downloadUrl(identifier, f.name)).copy(
                title = section.title.trim(), trackNo = i + 1, readers = section.readers,
                durationMs = section.playtimeMs.takeIf { it > 0 } ?: f.lengthMs, sectionId = section.id,
            )
        }
        return MediaManifest(
            source = MediaManifest.LIBRIVOX,
            kind = MediaManifest.AUDIOBOOK,
            ref = book.id,
            archiveIdentifier = identifier,
            pageUrl = book.pageUrl,
            title = book.title,
            creators = book.authors,
            readers = offer.files.flatMap { it.first.readers }.distinct(),
            description = book.description,
            language = book.language,
            genres = book.genres,
            date = book.copyrightYear,
            totalTimeMs = book.totalTimeMs.takeIf { it > 0 } ?: files.sumOf { it.durationMs ?: 0L },
            cover = offer.cover?.let { manifestFile(COVER_NAME, it, client.downloadUrl(identifier, it.name)) },
            files = files,
            gotAt = now,
        )
    }

    private fun manifestFile(name: String, f: ArchiveFile, url: String): ManifestFile {
        val (hash, algorithm) = when {
            !f.sha1.isNullOrBlank() -> f.sha1!! to HashAlgorithm.SHA1
            !f.md5.isNullOrBlank() -> f.md5!! to HashAlgorithm.MD5
            else -> throw IllegalArgumentException("${f.name} has neither sha1 nor md5")
        }
        return ManifestFile(name = name, remoteName = f.name, url = url, sizeBytes = f.sizeBytes ?: 0L, hash = hash, hashAlgorithm = algorithm.name)
    }

    /** The cover first (if any), then every file in order: ItemKind.AUDIO, groupId set, title = MediaManifests.displayTitle(m), relativePath = relativeDir/name, sha1 else md5. */
    fun specs(m: MediaManifest): List<DownloadSpec> {
        val group = groupId(m)
        val dir = MediaManifests.relativeDir(m)
        val title = MediaManifests.displayTitle(m)
        fun spec(ordinal: Int, f: ManifestFile) = DownloadSpec(
            itemId = fileItemId(group, ordinal),
            kind = ItemKind.AUDIO,
            title = title,
            urls = listOf(f.url),
            sizeBytes = f.sizeBytes,
            hash = f.hash,
            hashAlgorithm = HashAlgorithm.valueOf(f.hashAlgorithm),
            relativePath = "$dir/${f.name}",
            snapshotDate = "",
            // Left-over cap plumbing: nothing in Media reads it (no storage cap).
            capped = false,
            groupId = group,
        )
        return listOfNotNull(m.cover?.let { spec(0, it) }) + m.files.mapIndexed { i, f -> spec(i + 1, f) }
    }

    /** Whether [record] is the group's cover, not one of its [fileCount] audio files. */
    fun isCover(record: DownloadRecord): Boolean = record.itemId.endsWith("/000") && File(record.filePath).name == COVER_NAME

    /**
     * Active → Queued/Downloading(bytes done / total)/Failed; all files installed → Installed; some installed, none
     * active → Partial; none → NotInstalled. A queued file is Queued (waiting: for Wi-Fi, a connection, or a retry)
     * once it has bytes or an error, or before anything has come in; a fresh file queued between two others is the
     * group still Downloading, so the page doesn't flash "Waiting" between files.
     */
    fun statusOf(groupId: String, fileCount: Int, records: List<DownloadRecord>): ItemStatus {
        val group = records.filter { it.groupId == groupId }
        if (group.isEmpty()) return ItemStatus.NotInstalled
        val running = group.any { it.state == DownloadState.RUNNING }
        // The whole rest of the group is queued behind the current file: the one that stopped (bytes or an error) speaks for it.
        val waiting = group.filter { it.state == DownloadState.QUEUED }
        val queued = waiting.firstOrNull { it.bytesDone > 0 || it.error != null } ?: waiting.firstOrNull()
        if (running || queued != null) {
            if (running) return ItemStatus.Downloading(fractionOf(group))
            val stalled = queued!!.bytesDone > 0 || queued.error != null
            val started = group.any { it.state == DownloadState.INSTALLED && !isCover(it) }
            if (!stalled && started) return ItemStatus.Downloading(fractionOf(group))
            return ItemStatus.Queued(queued.wifiOnly, queued.error)
        }
        group.firstOrNull { it.state == DownloadState.FAILED }?.let { return ItemStatus.Failed(it.error ?: "Download failed") }
        val installed = group.filter { it.state == DownloadState.INSTALLED && !isCover(it) }.map { it.itemId }.distinct().size
        return when {
            installed >= fileCount -> ItemStatus.Installed
            installed > 0 -> ItemStatus.Partial(installed, fileCount)
            else -> ItemStatus.NotInstalled
        }
    }

    private fun fractionOf(group: List<DownloadRecord>): Float {
        val total = group.sumOf { it.sizeBytes }
        val done = group.sumOf { if (it.state == DownloadState.INSTALLED) it.sizeBytes else it.bytesDone.coerceIn(0, it.sizeBytes) }
        return if (total > 0) done.toFloat() / total else 0f
    }

    /** A download group's state for a book or album page: its [status], and its audio files in ([done]) and in all. */
    data class Progress(val status: ItemStatus, val done: Int, val total: Int)

    /** The group a downloaded audiobook came from; null for phone media. */
    fun groupOf(book: Audiobook): String? = when (book.origin) {
        MediaOrigin.PHONE -> null
        MediaOrigin.LIBRIVOX -> groupId(MediaManifest.LIBRIVOX, book.sourceRef)
        MediaOrigin.ARCHIVE_ORG -> groupId(MediaManifest.ARCHIVE_ORG, book.sourceRef)
    }

    /** The group a downloaded album came from ([Albums.itemKey]); null for a phone album. */
    fun groupOfAlbum(albumKey: String): String? = listOf(MediaManifest.ARCHIVE_ORG, MediaManifest.LIBRIVOX).firstNotNullOfOrNull { source ->
        val prefix = Albums.itemKey(source, "")
        albumKey.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }?.let { groupId(source, it) }
    }

    /** The number of audio files [groupId]'s manifest lists, read beside its records; null when none can be read. Reads the disk. */
    fun manifestFileCount(groupId: String, records: List<DownloadRecord>): Int? = records.asSequence()
        .filter { it.groupId == groupId }
        .firstNotNullOfOrNull { r -> File(r.filePath).parentFile?.let(MediaManifests::read)?.files?.size }

    /**
     * How far [groupId] is, out of [fileCount] audio files (from [manifestFileCount]: the ledger drops a cancelled
     * file's record), else as many as it has records. An item filed from disk with no records at all is in.
     */
    fun progress(groupId: String, records: List<DownloadRecord>, fileCount: Int?): Progress {
        val mine = records.filter { it.groupId == groupId }
        if (mine.isEmpty()) return Progress(ItemStatus.Installed, 0, 0)
        val audio = mine.filterNot(::isCover)
        val done = audio.filter { it.state == DownloadState.INSTALLED }.map { it.itemId }.distinct().size
        val total = fileCount ?: audio.map { it.itemId }.distinct().size
        return Progress(statusOf(groupId, total, records), done, total)
    }
}
