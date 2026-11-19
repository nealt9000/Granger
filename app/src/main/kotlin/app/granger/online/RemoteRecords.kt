package app.granger.online

import app.granger.core.catalog.RecordsEntry
import app.granger.core.library.CorruptBlock
import app.granger.core.library.RecordsDirectory
import app.granger.core.library.TopicRecord
import app.granger.core.library.TopicRecords
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** A topic record for an article by its QID (Plan O6), or null when none is to be had now. Blocking. */
fun interface RecordsSource {
    fun record(language: String, qid: Long, packId: String): TopicRecord?
}

/**
 * Topic records read online by HTTP range (Plan O6; spec §6 Phase 2): one ~12 KB block a page from the GitHub release
 * the catalog names, after the language's directory, which is fetched once and kept.
 *
 * - **The directory** ([dirFolder], `<lang>_<sha256>.grdir`) is fetched the first time a page needs it (builder's call 4)
 *   and checked against the catalog's sha256. A new edition replaces the language's old one.
 * - **Blocks** go into Saved for now ([saved], kind RECORDS), inside its cap and cleared by its Clear (builder's call 5),
 *   so a page seen once opens again with no signal. Each is checked against its CRC32 before it's read.
 * - **Offline** (Local only, no signal, or before the privacy line) only kept blocks are read: [canFetch] says no.
 * - **The release URL** redirects to a signed CDN address. It's resolved once and reused, and again on a 403 or 410
 *   when the signature has expired (builder's call 6).
 *
 * Every request goes through [http], the app's one client: the network gate and the User-Agent stay in front.
 */
class RemoteRecords(
    private val http: OkHttpClient,
    /** The catalog's records for a language; null without any. */
    private val entryFor: (language: String) -> RecordsEntry?,
    private val dirFolder: File,
    private val saved: SavedStore?,
    /** Whether a request may go out now: online, Local only off, the privacy line seen. */
    private val canFetch: () -> Boolean,
    /** Keeps Saved for now under its cap after a block is added. */
    private val trim: () -> Unit = {},
) : RecordsSource {
    private val directories = ConcurrentHashMap<String, RecordsDirectory>()
    private val resolved = ConcurrentHashMap<String, String>()
    private val gates = ConcurrentHashMap<String, Semaphore>()
    private val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    override fun record(language: String, qid: Long, packId: String): TopicRecord? {
        val entry = entryFor(language) ?: return null
        val directory = directory(language, entry) ?: return null
        val block = directory.blockFor(qid) ?: return null
        val key = blockKey(entry, block.index)
        saved?.get(key)?.bytes?.let { bytes ->
            try {
                return TopicRecords.find(bytes, block, qid, packId)
            } catch (_: CorruptBlock) {
                saved.remove(key)
            }
        }
        if (!canFetch()) return null
        repeat(2) { attempt ->
            val bytes = range(entry.url, block.offset, block.length, retried = attempt > 0) ?: return null
            try {
                val record = TopicRecords.find(bytes, block, qid, packId)
                saved?.put(key, SavedStore.Kind.RECORDS, bytes, OCTETS)
                trim()
                return record
            } catch (_: CorruptBlock) {
                resolved.remove(entry.url)   // a bad read, or a file that moved: ask again once from the top
            }
        }
        return null
    }

    /** The language's directory: in memory, else the kept file, else fetched and checked (only when [canFetch]). */
    private fun directory(language: String, entry: RecordsEntry): RecordsDirectory? {
        directories[entry.dirSha256]?.let { return it }
        val file = File(dirFolder, "${language}_${entry.dirSha256}.grdir")
        val bytes = if (file.isFile) file.readBytes() else {
            if (!canFetch()) return null
            val fetched = get(entry.dirUrl) ?: return null
            if (sha256(fetched) != entry.dirSha256) return null
            dirFolder.mkdirs()
            val tmp = File(dirFolder, file.name + ".part")
            runCatching { tmp.writeBytes(fetched); if (!tmp.renameTo(file)) tmp.delete() }
            // The language's older editions go.
            dirFolder.listFiles { f -> f.name.startsWith("${language}_") && f.name != file.name }?.forEach { it.delete() }
            fetched
        }
        val parsed = RecordsDirectory.parse(bytes) ?: return null.also { file.delete() }
        directories[entry.dirSha256] = parsed
        return parsed
    }

    private fun get(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }.getOrNull()

    /**
     * [length] bytes of [url] from [offset], or null. Only a 206 of exactly that length counts: a server that ignores
     * the range would send the whole file, and that's never read.
     */
    private fun range(url: String, offset: Long, length: Int, retried: Boolean): ByteArray? {
        val target = resolved[url] ?: resolve(url) ?: return null
        val gate = gates.getOrPut(hostOf(target)) { Semaphore(PER_HOST) }
        gate.acquire()
        try {
            val request = Request.Builder().url(target).header("Range", "bytes=$offset-${offset + length - 1}").build()
            val result = runCatching {
                noRedirects.newCall(request).execute().use { r ->
                    when {
                        r.code == 206 -> r.body.bytes().takeIf { it.size == length }
                        // The signed CDN address has expired: resolve it again, once.
                        r.code in EXPIRED && target != url && !retried -> { resolved.remove(url); RESOLVE_AGAIN }
                        else -> null
                    }
                }
            }.getOrNull()
            return if (result === RESOLVE_AGAIN) range(url, offset, length, retried = true) else result
        } finally {
            gate.release()
        }
    }

    /** Where [url] redirects (GitHub's release address to its signed CDN one), remembered for the session. */
    private fun resolve(url: String): String? = runCatching {
        noRedirects.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
            val location = r.header("Location")
            when {
                r.isRedirect && location != null -> r.request.url.resolve(location)?.toString()
                r.isSuccessful -> url
                else -> null
            }
        }
    }.getOrNull()?.also { resolved[url] = it }

    companion object {
        private const val PER_HOST = 4
        private const val OCTETS = "application/octet-stream"
        private val EXPIRED = setOf(400, 403, 410)
        private val RESOLVE_AGAIN = ByteArray(0)

        /** A block's key in Saved for now: the edition (its directory's checksum) and the block's place. */
        fun blockKey(entry: RecordsEntry, block: Int) = "rec:${entry.dirSha256.take(16)}:$block"

        private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
