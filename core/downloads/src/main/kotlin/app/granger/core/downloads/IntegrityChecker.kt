package app.granger.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Readiness checks: a size check always, a full checksum re-hash on request (spec "Readiness"). */
object IntegrityChecker {
    /** The size check, a voice's unpacked folder included ([Integrity.quick], shared with the iPhone). */
    fun quick(record: DownloadRecord): Integrity = Integrity.quick(record)

    suspend fun full(
        record: DownloadRecord,
        io: CoroutineDispatcher = Dispatchers.IO,
        onProgress: (Float) -> Unit = {},
    ): Integrity = withContext(io) {
        if (record.kind == ItemKind.VOICE) return@withContext VoiceUnpacker.full(record, io, onProgress)
        val file = record.file
        if (!file.isFile) return@withContext Integrity.MISSING
        if (file.length() != record.sizeBytes) return@withContext Integrity.INCOMPLETE
        if (record.hash.startsWith(UNVERIFIED_PREFIX)) return@withContext Integrity.SIZE_OK
        val digest = MessageDigest.getInstance(record.hashAlgorithm.jcaName)
        hashFile(file, digest) { onProgress(it.toFloat() / record.sizeBytes) }
        if (digest.digest().toHex() == record.hash.lowercase()) Integrity.VERIFIED else Integrity.CORRUPTED
    }
}
