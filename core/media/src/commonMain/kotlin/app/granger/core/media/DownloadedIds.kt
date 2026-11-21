package app.granger.core.media

import app.granger.core.library.MediaOrigin
import app.granger.core.text.hex
import app.granger.core.text.sha1

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
        val part = f.sectionId?.takeIf { m.source == MediaManifest.LIBRIVOX && it.isNotBlank() } ?: hash16(f.remoteName)
        return "track:${m.source}:${m.ref}:$part"
    }

    private fun hash16(s: String) = sha1(s.encodeToByteArray()).hex().take(16)
}
