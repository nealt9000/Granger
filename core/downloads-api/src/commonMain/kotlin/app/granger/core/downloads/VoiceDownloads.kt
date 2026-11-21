package app.granger.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One file a voice's `manifest.json` lists: its path inside the voice directory, size and sha256. */
data class VoiceFile(val name: String, val sizeBytes: Long, val sha256: String)

/**
 * A read-aloud voice through the shared queue, on both phones (Plan V1, moved in Plan I5 Task 3). The catalog's
 * [app.granger.core.catalog.VoiceEntry] is queued as one zip, `voices/<id>.zip` on the chosen volume ([zipPath]); once
 * verified, it is unpacked into `voices/<id>/` ([voiceDir]) through `voices/<id>.part/` ([partDir]) so a half-unpacked
 * voice never looks installed, and the zip goes. Every entry must stay inside the directory ([isSafeEntry]) and the zip
 * must carry a `manifest.json`, which Readiness checks the unpacked files against ([quick], [full]). The unpacked
 * directory is what the voice list counts (`PiperVoices` in `:core:speech`: `tokens.txt`, `espeak-ng-data/` and one
 * `.onnx` model). Android unpacks with `java.util.zip` (`VoiceUnpacker`); the iPhone's unzip comes with its voices
 * (Plan I5b).
 */
object VoiceDownloads {
    /** The folder under each volume's root that holds the voices. */
    const val FOLDER = "voices"
    const val MANIFEST = "manifest.json"

    /** Where the queue puts a voice's zip, relative to the volume's root. */
    fun zipPath(id: String): String = "$FOLDER/$id.zip"

    /** The directory a voice zip unpacks into: `voices/<id>/` beside the zip. */
    fun voiceDir(zipPath: String): String {
        val name = zipPath.trimEnd('/').substringAfterLast('/').removeSuffix(".zip")
        return Files.parent(zipPath)?.let { Files.join(it, name) } ?: name
    }

    /** Where a voice is unpacked before it is moved into place. */
    fun partDir(zipPath: String): String = voiceDir(zipPath) + ".part"

    /** Whether a zip entry's [name] stays inside the voice directory (no absolute path, no `..` step). */
    fun isSafeEntry(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\")) return false
        return name.split('/', '\\').none { it == ".." }
    }

    /** A manifest's files, or null when [json] isn't a readable manifest. */
    fun parseManifest(json: String): List<VoiceFile>? = runCatching {
        Json.parseToJsonElement(json).jsonObject.getValue("files").jsonArray.map { e ->
            val f = e.jsonObject
            val size = f.getValue("sizeBytes").jsonPrimitive
            VoiceFile(
                name = f.getValue("name").jsonPrimitive.content,
                sizeBytes = size.longOrNull ?: size.content.toDouble().toLong(),
                sha256 = f.getValue("sha256").jsonPrimitive.content,
            )
        }
    }.getOrNull()

    /** The manifest's files in voice directory [dir], or null when it has no readable manifest. */
    fun manifest(dir: String): List<VoiceFile>? = runCatching {
        SystemFileSystem.source(Path(Files.join(dir, MANIFEST))).buffered().use { it.readString() }
    }.getOrNull()?.let(::parseManifest)

    /** Readiness' size check for an unpacked voice: the record's path is the zip's; the directory beside it is what is checked. */
    fun quick(record: DownloadRecord): Integrity {
        val dir = voiceDir(record.filePath)
        if (!Files.isDirectory(dir)) return Integrity.MISSING
        val files = manifest(dir) ?: return Integrity.CORRUPTED
        for (f in files) {
            val file = Files.join(dir, f.name)
            if (!Files.isFile(file)) return Integrity.MISSING
            if (Files.size(file) != f.sizeBytes) return Integrity.INCOMPLETE
        }
        return if (record.verifiedAt != null) Integrity.VERIFIED else Integrity.SIZE_OK
    }

    /** Readiness' full check: every file of the manifest re-hashed. A file that can't be read throws. */
    suspend fun full(record: DownloadRecord, io: CoroutineDispatcher = Dispatchers.IO, onProgress: (Float) -> Unit = {}): Integrity = withContext(io) {
        val sizes = quick(record)
        if (sizes != Integrity.SIZE_OK && sizes != Integrity.VERIFIED) return@withContext sizes
        val dir = voiceDir(record.filePath)
        val files = manifest(dir) ?: return@withContext Integrity.CORRUPTED
        val total = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
        var done = 0L
        for (f in files) {
            val digest = sha256File(Files.join(dir, f.name)) { inFile -> onProgress((done + inFile).toFloat() / total) }
            if (digest != f.sha256.lowercase()) return@withContext Integrity.CORRUPTED
            done += f.sizeBytes
        }
        Integrity.VERIFIED
    }
}

/**
 * The sha256 of the file at [path] as lower-case hex, read in pieces; [onBytes] gets the bytes read so far after each.
 * Throws when the file can't be read.
 */
internal expect fun sha256File(path: String, onBytes: (Long) -> Unit): String
