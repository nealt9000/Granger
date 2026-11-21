package app.granger.core.downloads

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A verified voice zip (`voices/<id>.zip`) becomes `voices/<id>/` and the zip is deleted (Plan V1, decision 4). The
 * zip is unpacked into `<id>.part/` first and renamed at the end, so a half-unpacked voice never looks installed.
 * Every entry must stay inside the directory (zip slip) and the zip must carry a `manifest.json`, which Readiness
 * checks the unpacked files against afterwards. The rules (paths, manifest, Readiness' checks) are shared with the
 * iPhone in [VoiceDownloads]; the unzip itself is Android's `java.util.zip`.
 */
object VoiceUnpacker {
    const val MANIFEST = VoiceDownloads.MANIFEST

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
    fun manifest(dir: File): List<VoiceFile>? = VoiceDownloads.manifest(dir.path)

    /** Readiness' size check for an unpacked voice ([VoiceDownloads.quick]). */
    fun quick(record: DownloadRecord): Integrity = VoiceDownloads.quick(record)

    /** Readiness' full check: every file of the manifest re-hashed ([VoiceDownloads.full]). */
    suspend fun full(record: DownloadRecord, io: CoroutineDispatcher = Dispatchers.IO, onProgress: (Float) -> Unit = {}): Integrity =
        VoiceDownloads.full(record, io, onProgress)
}
