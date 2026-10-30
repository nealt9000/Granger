package app.granger.sources

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.library.Library
import kotlinx.coroutines.flow.first

/**
 * Switch (Plan KB walkthrough, Neal: yes, the old one goes). Getting a pack's other flavour is a Switch, so once that
 * flavour has installed, any other flavour of the same pack on the phone gives way to it: its file and row go, and its
 * bookmarks, places, tabs and history move to the new one ([Library.supersede]; the flavours share their ZIM name).
 * Run only after an install, so a download that fails or is cancelled leaves the old flavour as it was.
 */
object FlavourSwitch {
    /** After [itemId]'s download installed (and the library has refreshed). Returns the ids of the flavours removed. */
    suspend fun settle(itemId: String, catalog: Catalog?, downloads: Downloads, library: Library): List<String> {
        val new = catalog?.item(itemId) as? PackEntry ?: return emptyList()
        // Only true flavours are one pack's (KiwixList.group); an unflavoured ZIM of the same name is a pack of its own.
        if (new.zimName.isBlank() || KiwixList.flavourOf(new).isBlank()) return emptyList()
        val records = downloads.records.first()
        val packs = library.packs.first()
        val newPackId = installedPackId(new, records, packs) ?: return emptyList()
        val removed = mutableListOf<String>()
        for (old in catalog.packs) {
            if (old.id == new.id || old.zimName != new.zimName || KiwixList.flavourOf(old).isBlank()) continue
            // A download of the old flavour still under way is the user's own doing; leave it be.
            if (records.any { it.itemId == old.id && (it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING) }) continue
            val oldPackId = installedPackId(old, records, packs) ?: continue
            if (oldPackId == newPackId || !library.supersede(oldPackId, newPackId)) continue
            downloads.remove(old.id)
            removed += old.id
        }
        return removed
    }
}
