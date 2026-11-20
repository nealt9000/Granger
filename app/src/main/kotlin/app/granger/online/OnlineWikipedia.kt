package app.granger.online

import app.granger.core.library.Library
import app.granger.core.zim.ZimArchive

/** The library, plus the online sources: `archive("wikipedia_en_online")` reads live from Wikimedia. */
class OnlineLibrary(
    private val base: Library,
    /** Debug only: hide the installed Wikipedia packs (Preview: no Wikipedia on the phone, Plan O1 Task 10). */
    hideWikipedia: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    private val online: () -> Map<String, ZimArchive>,
) : Library by base {
    override val packs: kotlinx.coroutines.flow.Flow<List<app.granger.core.library.Pack>> =
        kotlinx.coroutines.flow.combine(base.packs, hideWikipedia) { packs, hide -> if (hide) packs.filterNot { it.name.startsWith("wikipedia_") } else packs }

    override fun archive(packId: String): ZimArchive? = online()[packId] ?: base.archive(packId)
    override fun cachedArchive(packId: String): ZimArchive? = online()[packId] ?: base.cachedArchive(packId)
}

/** Plan O2's stores over the app's [SavedStore] (the shared [OfflineStores] takes the store underneath). */
fun OfflineStores(
    saved: SavedStore,
    kept: KeptStore,
    savePictures: () -> Boolean,
    capBytes: () -> Long,
    clock: () -> Long = System::currentTimeMillis,
    pageSaved: () -> Unit = {},
): OfflineStores = OfflineStores(saved.shared, kept, savePictures, capBytes, clock, pageSaved)
