package app.granger.library

import app.granger.core.library.Library
import app.granger.core.library.PackStatus
import app.granger.core.library.ReadingSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Opens the installed pack [packId] at its last-read page in a rail tab and returns the tab id, or null when the pack
 * can't be read. The tab is titled with the pack until the page reports its own title. The one way a pack opens, from
 * the Library, a source's Open and Downloads (Plan U3b Decision 3).
 *
 * [newTab] (Browse's pack) opens the pack's main page in a tab of its own instead, even when the pack is open on the
 * rail already, so one wiki can be open in two places to switch between (Plan U3c walkthrough).
 */
suspend fun openPack(library: Library, sessions: ReadingSessions, packId: String, newTab: Boolean = false): Long? {
    val pack = library.packs.first().firstOrNull { it.id == packId }?.takeIf { it.status == PackStatus.OK } ?: return null
    if (newTab) {
        val main = withContext(Dispatchers.IO) { library.archive(pack.id)?.mainPath } ?: return null
        return sessions.newTab(pack.id, main, pack.title)
    }
    val path = library.startPath(pack.id) ?: return null
    return sessions.openTab(pack.id, path, pack.title)
}
