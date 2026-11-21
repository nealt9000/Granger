package app.granger.core.media

import app.granger.core.text.hex
import app.granger.core.text.sha1

/**
 * A phone file's identity: survives moves and renames, not edits to its ends (where tags live). sha1 over "$size:",
 * the first 64 KiB and the last 64 KiB (they overlap in a file under 128 KiB), first 16 hex chars. Android reads the
 * ends from a `FileChannel` (androidMain).
 */
object Fingerprint {
    const val CHUNK_BYTES = 64 * 1024

    fun of(sizeBytes: Long, head: ByteArray, tail: ByteArray): String =
        sha1("$sizeBytes:".encodeToByteArray() + head + tail).hex().take(16)
}
