package app.granger.core.online

/**
 * What Wikipedia online reads through (Plan O2's stores): Saved for now and kept articles. Android implements it over
 * its `SavedStore` and `KeptStore` (`OfflineStores` in `:app`); iOS over its own (I3). Common since Plan I1.
 */
interface OnlineStores {
    class Saved(val bytes: ByteArray, val mime: String, val savedAt: Long)

    enum class SavedKind { PAGE, PICTURE, SUMMARY }

    /** Saved for now's copy of [key], marked as opened; null when there's none. */
    fun saved(key: String): Saved?

    /** Saves [bytes] for now under [key], keeping the store under its cap. */
    fun save(key: String, kind: SavedKind, bytes: ByteArray, mime: String)

    /** Whether [title] is kept with its pictures (true), as text only (false), or not kept (null). */
    fun keptWithPictures(lang: String, title: String): Boolean?

    fun keptPage(lang: String, title: String): ByteArray?

    fun keptPicture(path: String): ByteArray?

    /** Offline pictures: true for Save pictures (the default), false for Text only. */
    fun savePictures(): Boolean

    fun now(): Long

    /** A page was saved for now: its marks are worked out again. */
    fun pageSaved() {}

    companion object {
        /** Saved for now within this long is preferred to online (Back is instant). */
        const val FRESH_MS = 60L * 60 * 1000

        fun pageKey(lang: String, title: String) = "$lang:page:$title"
        fun summaryKey(lang: String, title: String) = "$lang:sum:$title"
        fun pictureKey(path: String) = "img:$path"
    }
}

/** A kept article's files before they're written: its page and its pictures by reader path. */
class KeepContent(val page: ByteArray, val pictures: Map<String, ByteArray>)
