package app.granger.library

import app.granger.core.library.Library
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import kotlinx.coroutines.flow.first

/**
 * Opens the installed pack [packId] at its last-read page in a rail tab and returns the tab id, or null when the pack
 * can't be read. The tab is titled with the pack until the page reports its own title. The one way a pack opens, from
 * the Library, a source's Open and Downloads (Plan U3b Decision 3).
 */
suspend fun openPack(library: Library, sessions: ReadingSessions, packId: String): Long? {
    val pack = library.packs.first().firstOrNull { it.id == packId }?.takeIf { it.status == PackStatus.OK } ?: return null
    val path = library.startPath(pack.id) ?: return null
    return sessions.openTab(pack.id, path, pack.title)
}
