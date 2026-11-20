package app.granger.online

import app.granger.core.library.SavedForNow
import java.io.File
import java.security.MessageDigest

/**
 * Saved for now (online/offline spec §3, §5; Plan O2 Task 1): what was read online (prepared pages, pictures,
 * summaries, books opened without keeping), kept so Back is instant and it still opens with no signal, removed oldest-
 * opened first once the store passes its cap. Never counted as offline coverage: it's a cache, not a choice.
 *
 * Since Plan I3 a thin wrapper over the shared [SavedForNow] (the iPhone's store too), with the same files and index
 * as before: files under [dir] by the SHA-1 of their key, beside `index.db`. In the app's files, not its cache, so
 * Android doesn't clear it behind the reader's back (builder's call 2). Blocking; safe from several threads.
 */
class SavedStore(private val dir: File, clock: () -> Long = System::currentTimeMillis) {
    enum class Kind { PAGE, PICTURE, SUMMARY, BOOK, RECORDS }

    class Saved(val bytes: ByteArray, val mime: String, val savedAt: Long)

    /** The shared store underneath, for code in `:core:screens`. */
    val shared = SavedForNow(dir.path, clock)

    fun put(key: String, kind: Kind, bytes: ByteArray, mime: String) = shared.put(key, kind.name, bytes, mime)

    /** [file] moved into the store under [key] (a book opened without keeping); true when it's in. */
    fun putFile(key: String, kind: Kind, file: File, mime: String): Boolean = shared.putFile(key, kind.name, file.path, mime)

    /** The saved bytes for [key], marked as opened now; null when there are none. */
    fun get(key: String): Saved? = shared.get(key)?.let { Saved(it.bytes, it.mime, it.savedAt) }

    /** The file behind [key] (a saved book, opened in place), marked as opened now; null when it isn't saved. */
    fun file(key: String): File? = shared.file(key)?.let(::File)

    /** The saved size of [key] in bytes; null when it isn't saved. */
    fun bytesOf(key: String): Long? = shared.bytesOf(key)

    fun has(key: String): Boolean = shared.has(key)

    /** Takes [key] out of the store, its file with it (Keep moves a saved book onto the shelf). */
    fun remove(key: String) = shared.remove(key)

    /** Bytes in the store. */
    fun size(): Long = shared.size()

    /** Removes the oldest-opened until the store is at most [capBytes]. */
    fun trim(capBytes: Long) = shared.trim(capBytes)

    fun clear() = shared.clear()

    companion object {
        /** A saved page under this age opens at once even online (builder's call 2); older ones are fetched fresh. */
        const val FRESH_MS = 60L * 60 * 1000

        fun pageKey(lang: String, title: String) = "$lang:page:$title"
        fun summaryKey(lang: String, title: String) = "$lang:sum:$title"
        fun pictureKey(path: String) = "img:$path"
        fun bookKey(bookId: String) = "book:$bookId"

        internal fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
