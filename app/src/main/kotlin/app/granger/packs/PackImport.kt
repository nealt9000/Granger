package app.granger.packs

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadSpec
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.UNVERIFIED_PREFIX
import app.granger.core.downloads.downloadSpecOf

/**
 * Adding a pack from a file (Plan E1 item 4, Neal 2026-11-03): a `.zim` on the phone, an SD card or a USB stick, copied
 * into the pack folder through the download queue. Off-grid, one download in town can fill many phones.
 */
object PackImport {
    /** What the picker gave: the file's address, name, size, and its first bytes (the ZIM header). */
    data class Picked(val url: String, val name: String, val sizeBytes: Long, val header: ByteArray)

    /** ZIM's magic number, little-endian, at the start of every ZIM file. */
    private const val ZIM_MAGIC = 72_173_914 // 0x044D495A
    const val HEADER_BYTES = 24

    /**
     * The ZIM's UUID as libkiwix and the catalog write it (`027a0fa5-9ecd-dc06-364d-e56dd4b65588`: bytes 8–23 in order,
     * checked against a Kiwix file 2026-11-03), or null when [header] isn't a ZIM's.
     */
    fun uuidOf(header: ByteArray): String? {
        if (header.size < HEADER_BYTES) return null
        val magic = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8) or
            ((header[2].toInt() and 0xff) shl 16) or ((header[3].toInt() and 0xff) shl 24)
        if (magic != ZIM_MAGIC) return null
        val hex = (8 until 24).joinToString("") { "%02x".format(header[it].toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /**
     * The download that copies [picked] in. A file the catalog lists (same UUID and size) is filed as that entry, so
     * Readiness, updates and the checksum work as for a download; any other ZIM is kept under its own name, unchecked.
     */
    fun spec(picked: Picked, uuid: String, catalog: Catalog?): DownloadSpec {
        val listed = catalog?.packs?.firstOrNull { it.zimUuid.equals(uuid, ignoreCase = true) && it.sizeBytes == picked.sizeBytes }
        if (listed != null) return downloadSpecOf(listed).copy(urls = listOf(picked.url))
        val base = picked.name.removeSuffix(".zim").removeSuffix(".ZIM").ifBlank { uuid }
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return DownloadSpec(
            itemId = "$IMPORT_PREFIX$uuid", kind = ItemKind.PACK, title = base, urls = listOf(picked.url),
            sizeBytes = picked.sizeBytes, hash = "$UNVERIFIED_PREFIX$uuid", hashAlgorithm = HashAlgorithm.SHA256,
            relativePath = "packs/${safe}.zim", snapshotDate = "",
        )
    }

    /** Item ids of packs added from a file that the catalog doesn't list. */
    const val IMPORT_PREFIX = "import:"

    /** The catalog entry [spec] was filed as, if any. */
    fun listedEntry(spec: DownloadSpec, catalog: Catalog?): PackEntry? = catalog?.packs?.firstOrNull { it.id == spec.itemId }
}
