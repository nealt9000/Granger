package app.granger.core.online

import app.granger.core.catalog.RecordsEntry
import app.granger.core.library.CorruptBlock
import app.granger.core.library.RecordsDirectory
import app.granger.core.library.TopicRecord
import app.granger.core.library.TopicRecords
import app.granger.core.text.hex
import app.granger.core.text.sha256
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** A topic record for an article by its QID (Plan O6), or null when none is to be had now. Blocking. */
fun interface RecordsSource {
    fun record(language: String, qid: Long, packId: String): TopicRecord?
}

/** Where record blocks are saved for now (Android: its SavedStore, kind RECORDS, inside its cap; iOS: the same layout). */
interface SavedBlocks {
    fun get(key: String): ByteArray?
    fun put(key: String, bytes: ByteArray)
    fun remove(key: String)
}

/**
 * Topic records read online by HTTP range (Plan O6; spec §6 Phase 2): one ~12 KB block a page from the GitHub release
 * the catalog names, after the language's directory, which is fetched once and kept. Common to both phones since
 * Plan I3 Task 3.
 *
 * - **The directory** ([dirFolder], `<lang>_<sha256>.grdir`) is fetched the first time a page needs it and checked
 *   against the catalog's sha256. A new edition replaces the language's old one.
 * - **Blocks** go into Saved for now ([saved]), so a page seen once opens again with no signal. Each is checked against
 *   its CRC32 before it's read.
 * - **Offline** (Local only, no signal, or before the privacy line) only kept blocks are read: [canFetch] says no.
 * - **The release URL** redirects to a signed CDN address. It's resolved once and reused, and again on a 400, 403 or
 *   410 when the signature has expired.
 *
 * Every request goes through [http], the app's one fetch: the network gate and the User-Agent stay in front.
 */
class RemoteRecords(
    private val http: HttpFetch,
    /** The catalog's records for a language; null without any. */
    private val entryFor: (language: String) -> RecordsEntry?,
    private val dirFolder: String,
    private val saved: SavedBlocks?,
    /** Whether a request may go out now: online, Local only off, the privacy line seen. */
    private val canFetch: () -> Boolean,
) : RecordsSource {
    private val lock = SyncLock()
    private val directories = HashMap<String, RecordsDirectory>()
    private val resolved = HashMap<String, String>()
    private val gates = HostGates(PER_HOST)

    override fun record(language: String, qid: Long, packId: String): TopicRecord? {
        val entry = entryFor(language) ?: return null.also { debug("no records for $language") }
        val directory = directory(language, entry) ?: return null.also { debug("no directory for $language (canFetch ${canFetch()})") }
        val block = directory.blockFor(qid) ?: return null
        val key = blockKey(entry, block.index)
        saved?.get(key)?.let { bytes ->
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
                saved?.put(key, bytes)
                return record
            } catch (_: CorruptBlock) {
                lock.with { resolved.remove(entry.url) }   // a bad read, or a file that moved: ask again once from the top
            }
        }
        return null
    }

    /** The language's directory: in memory, else the kept file, else fetched and checked (only when [canFetch]). */
    private fun directory(language: String, entry: RecordsEntry): RecordsDirectory? {
        lock.with { directories[entry.dirSha256] }?.let { return it }
        val file = Path(dirFolder, "${language}_${entry.dirSha256}.grdir")
        val kept = if (SystemFileSystem.exists(file)) runCatching { SystemFileSystem.source(file).buffered().use { it.readByteArray() } }.getOrNull() else null
        val bytes = kept ?: run {
                if (!canFetch()) return null
                val fetched = http.send(HttpRequest(entry.dirUrl))?.takeIf { it.ok }?.body ?: return null.also { debug("GET ${entry.dirUrl} failed") }
                if (sha256(fetched).hex() != entry.dirSha256) return null.also { debug("directory checksum mismatch (${fetched.size} bytes)") }
                runCatching {
                    SystemFileSystem.createDirectories(Path(dirFolder))
                    val tmp = Path(dirFolder, file.name + ".part")
                    SystemFileSystem.sink(tmp).buffered().use { it.write(fetched) }
                    SystemFileSystem.atomicMove(tmp, file)
                    // The language's older editions go.
                    SystemFileSystem.list(Path(dirFolder)).filter { it.name.startsWith("${language}_") && it.name != file.name }
                        .forEach { runCatching { SystemFileSystem.delete(it) } }
                }
                fetched
            }
        val parsed = RecordsDirectory.parse(bytes) ?: return null.also { runCatching { SystemFileSystem.delete(file, mustExist = false) } }
        lock.with { directories[entry.dirSha256] = parsed }
        return parsed
    }

    private fun debug(message: String) = OnlineLog.info("records: $message")

    /**
     * [length] bytes of [url] from [offset], or null. Only a 206 of exactly that length counts: a server that ignores
     * the range would send the whole file, and that's never read.
     */
    private fun range(url: String, offset: Long, length: Int, retried: Boolean): ByteArray? {
        val target = lock.with { resolved[url] } ?: resolve(url) ?: return null
        val host = UrlCodec.hostOf(target) ?: return null
        val reply = gates.through(host) {
            runCatching {
                http.send(HttpRequest(target, headers = mapOf("Range" to "bytes=$offset-${offset + length - 1}"), followRedirects = false))
            }.getOrNull()
        } ?: return null
        return when {
            reply.code == 206 -> reply.body.takeIf { it.size == length }
            // The signed CDN address has expired: resolve it again, once.
            reply.code in EXPIRED && target != url && !retried -> {
                lock.with { resolved.remove(url) }
                range(url, offset, length, retried = true)
            }
            else -> null
        }
    }

    /** Where [url] redirects (GitHub's release address to its signed CDN one), remembered for the session. */
    private fun resolve(url: String): String? {
        val reply = runCatching { http.send(HttpRequest(url, method = "HEAD", followRedirects = false)) }.getOrNull() ?: return null
        val location = reply.header("Location")
        val target = when {
            reply.isRedirect && location != null -> resolveAgainst(url, location)
            reply.ok -> url
            else -> null
        } ?: return null
        lock.with { resolved[url] = target }
        return target
    }

    companion object {
        private const val PER_HOST = 4
        private val EXPIRED = setOf(400, 403, 410)

        /** A block's key in Saved for now: the edition (its directory's checksum) and the block's place. */
        fun blockKey(entry: RecordsEntry, block: Int) = "rec:${entry.dirSha256.take(16)}:$block"

        /** [location] (absolute, host-relative or path-relative) against [base]. */
        internal fun resolveAgainst(base: String, location: String): String = when {
            location.startsWith("http://") || location.startsWith("https://") -> location
            location.startsWith("//") -> base.substringBefore("//") + location
            location.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + location
            else -> base.substringBeforeLast('/') + "/" + location
        }
    }
}
