package app.granger.online

import app.granger.core.library.Library
import app.granger.core.online.OnlineStores
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

/** Plan O2's stores, as Wikipedia online (`:core:online`, Plan I1) reads through them. */
class OfflineStores(
    val saved: SavedStore,
    val kept: KeptStore,
    /** Offline pictures: true for Save pictures (the default), false for Text only. */
    val savePictures: () -> Boolean,
    /** Saved for now's cap, in bytes. */
    private val capBytes: () -> Long,
    val clock: () -> Long = System::currentTimeMillis,
    /** A page was saved for now: its marks are worked out again. */
    val pageSaved: () -> Unit = {},
) : OnlineStores {
    /** Keeps Saved for now under its cap (each put adds a little; a trim only works when it's over). */
    fun trimmed() = saved.trim(capBytes())

    override fun saved(key: String): OnlineStores.Saved? = saved.get(key)?.let { OnlineStores.Saved(it.bytes, it.mime, it.savedAt) }

    override fun save(key: String, kind: OnlineStores.SavedKind, bytes: ByteArray, mime: String) {
        saved.put(key, SavedStore.Kind.valueOf(kind.name), bytes, mime)
        trimmed()
    }

    override fun keptWithPictures(lang: String, title: String): Boolean? = kept.get(lang, title)?.withPictures

    override fun keptPage(lang: String, title: String): ByteArray? = kept.page(lang, title)

    override fun keptPicture(path: String): ByteArray? = kept.picture(path)

    override fun savePictures(): Boolean = savePictures.invoke()

    override fun now(): Long = clock()

    override fun pageSaved() = pageSaved.invoke()
}
