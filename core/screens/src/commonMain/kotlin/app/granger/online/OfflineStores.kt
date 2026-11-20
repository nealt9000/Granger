package app.granger.online

import app.granger.core.library.SavedForNow
import app.granger.core.online.OnlineStores

/** Plan O2's stores, as Wikipedia online (`:core:online`, Plan I1) reads through them. */
class OfflineStores(
    val saved: SavedForNow,
    val kept: KeptStore,
    /** Offline pictures: true for Save pictures (the default), false for Text only. */
    val savePictures: () -> Boolean,
    /** Saved for now's cap, in bytes. */
    private val capBytes: () -> Long,
    val clock: () -> Long = { app.granger.screens.nowMillis() },
    /** A page was saved for now: its marks are worked out again. */
    val pageSaved: () -> Unit = {},
) : OnlineStores {
    /** Keeps Saved for now under its cap (each put adds a little; a trim only works when it's over). */
    fun trimmed() = saved.trim(capBytes())

    override fun saved(key: String): OnlineStores.Saved? = saved.get(key)?.let { OnlineStores.Saved(it.bytes, it.mime, it.savedAt) }

    override fun save(key: String, kind: OnlineStores.SavedKind, bytes: ByteArray, mime: String) {
        saved.put(key, kind.name, bytes, mime)
        trimmed()
    }

    override fun keptWithPictures(lang: String, title: String): Boolean? = kept.get(lang, title)?.withPictures

    override fun keptPage(lang: String, title: String): ByteArray? = kept.page(lang, title)

    override fun keptPicture(path: String): ByteArray? = kept.picture(path)

    override fun savePictures(): Boolean = savePictures.invoke()

    override fun now(): Long = clock()

    override fun pageSaved() = pageSaved.invoke()
}
