package app.granger.core.downloads

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One file a voice's `manifest.json` lists: its path inside the voice directory, size and sha256. */
data class VoiceFile(val name: String, val sizeBytes: Long, val sha256: String)

/**
 * A verified voice zip (`voices/<id>.zip`) becomes `voices/<id>/` and the zip is deleted (Plan V1, decision 4). The
 * zip is unpacked into `<id>.part/` first and renamed at the end, so a half-unpacked voice never looks installed.
 * Every entry must stay inside the directory (zip slip) and the zip must carry a `manifest.json`, which Readiness
 * checks the unpacked files against afterwards ([voiceIntegrity]).
 */
object VoiceUnpacker {
    const val MANIFEST = "manifest.json"

    /** The directory a voice zip unpacks into: `voices/<id>/` beside the zip. */
    fun voiceDir(zip: File): File = File(zip.parentFile, zip.name.removeSuffix(".zip"))

    suspend fun unpack(zip: File, io: CoroutineDispatcher = Dispatchers.IO): Result<File> = withContext(io) {
        runCatching {
            val target = voiceDir(zip)
            val part = File(target.parentFile, target.name + ".part")
            part.deleteRecursively()
            part.mkdirs()
            val canonical = part.canonicalPath + File.separator
            ZipFile(zip).use { z ->
                var manifest = false
                for (entry in z.entries()) {
                    if (entry.isDirectory) continue
                    val out = File(part, entry.name)
                    if (!out.canonicalPath.startsWith(canonical)) throw IOException("Refusing ${entry.name}: outside the voice directory")
                    out.parentFile?.mkdirs()
                    z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    if (entry.name == MANIFEST) manifest = true
                }
                if (!manifest) throw IOException("No $MANIFEST in ${zip.name}")
            }
            target.deleteRecursively()
            if (!part.renameTo(target)) throw IOException("Couldn't move ${part.name} into place")
            zip.delete()
            target
        }.onFailure { File(zip.parentFile, voiceDir(zip).name + ".part").deleteRecursively() }
    }

    /** The manifest's files, or null when the directory has no readable manifest. */
    fun manifest(dir: File): List<VoiceFile>? = runCatching {
        val json = JSONObject(File(dir, MANIFEST).readText())
        val files = json.getJSONArray("files")
        (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            VoiceFile(f.getString("name"), f.getLong("sizeBytes"), f.getString("sha256"))
        }
    }.getOrNull()

    /** Readiness' size check for an unpacked voice: the record's path is the zip's; the directory beside it is what is checked. */
    fun quick(record: DownloadRecord): Integrity {
        val dir = voiceDir(record.file)
        if (!dir.isDirectory) return Integrity.MISSING
        val files = manifest(dir) ?: return Integrity.CORRUPTED
        for (f in files) {
            val file = File(dir, f.name)
            if (!file.isFile) return Integrity.MISSING
            if (file.length() != f.sizeBytes) return Integrity.INCOMPLETE
        }
        return if (record.verifiedAt != null) Integrity.VERIFIED else Integrity.SIZE_OK
    }

    /** Readiness' full check: every file of the manifest re-hashed. */
    suspend fun full(record: DownloadRecord, io: CoroutineDispatcher = Dispatchers.IO, onProgress: (Float) -> Unit = {}): Integrity = withContext(io) {
        val sizes = quick(record)
        if (sizes != Integrity.SIZE_OK && sizes != Integrity.VERIFIED) return@withContext sizes
        val dir = voiceDir(record.file)
        val files = manifest(dir) ?: return@withContext Integrity.CORRUPTED
        val total = files.sumOf { it.sizeBytes }.coerceAtLeast(1)
        var done = 0L
        for (f in files) {
            val file = File(dir, f.name)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var read = input.read(buffer)
                var inFile = 0L
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    inFile += read
                    onProgress((done + inFile).toFloat() / total)
                    read = input.read(buffer)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != f.sha256.lowercase()) return@withContext Integrity.CORRUPTED
            done += f.sizeBytes
        }
        Integrity.VERIFIED
    }
}
