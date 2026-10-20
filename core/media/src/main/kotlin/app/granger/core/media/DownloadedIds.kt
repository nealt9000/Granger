package app.granger.core.media

import app.granger.core.library.MediaOrigin
import java.security.MessageDigest

/** Library ids of downloaded media (spec §10). None contains "@", so `MediaAddress` works on them unchanged. */
object DownloadedIds {
    fun origin(m: MediaManifest): MediaOrigin = when (m.source) {
        MediaManifest.LIBRIVOX -> MediaOrigin.LIBRIVOX
        MediaManifest.ARCHIVE_ORG -> MediaOrigin.ARCHIVE_ORG
        else -> throw IllegalArgumentException("Unknown source ${m.source}")
    }

    /** audiobook:librivox:<id> / audiobook:archive-org:<identifier>. */
    fun audiobookId(m: MediaManifest): String = "audiobook:${m.source}:${m.ref}"

    /**
     * track:librivox:<id>:<sectionId> / track:archive-org:<identifier>:<first 16 hex of sha1(remoteName)>. A LibriVox file
     * without a section id falls back to the archive.org form's hash.
     */
    fun trackId(m: MediaManifest, f: ManifestFile): String {
        val part = f.sectionId?.takeIf { m.source == MediaManifest.LIBRIVOX && it.isNotBlank() } ?: sha1(f.remoteName)
        return "track:${m.source}:${m.ref}:$part"
    }

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
