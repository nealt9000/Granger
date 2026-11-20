package app.granger.online

import app.granger.core.catalog.RecordsEntry
import app.granger.core.online.SavedBlocks
import app.granger.core.online.okHttpFetch
import okhttp3.OkHttpClient
import java.io.File

/**
 * Topic records read online (Plan O6), now shared with the iPhone (`app.granger.core.online.RemoteRecords`, Plan I3
 * Task 3). This builds it as the app always did: on its one OkHttpClient (the network gate and the User-Agent in front),
 * its blocks in Saved for now (kind RECORDS) and kept under the cap by [trim].
 */
fun RemoteRecords(
    http: OkHttpClient,
    entryFor: (language: String) -> RecordsEntry?,
    dirFolder: File,
    saved: SavedStore?,
    canFetch: () -> Boolean,
    trim: () -> Unit = {},
): app.granger.core.online.RemoteRecords =
    app.granger.core.online.RemoteRecords(okHttpFetch(http), entryFor, dirFolder.path, saved?.let { SavedStoreBlocks(it, trim) }, canFetch)

/** Record blocks in Saved for now. */
class SavedStoreBlocks(private val store: SavedStore, private val trim: () -> Unit) : SavedBlocks {
    override fun get(key: String): ByteArray? = store.get(key)?.bytes
    override fun put(key: String, bytes: ByteArray) {
        store.put(key, SavedStore.Kind.RECORDS, bytes, "application/octet-stream")
        trim()
    }
    override fun remove(key: String) = store.remove(key)
}
