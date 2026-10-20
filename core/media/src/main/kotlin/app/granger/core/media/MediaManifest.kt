package app.granger.core.media

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A downloaded file's tags, read once (on filing) and kept in the manifest so later scans never open the file. [title] is
 * the file's own TITLE tag ("" when it has none); [durationMs] is 0 when unknown. [embeddedCover] is the local name of the
 * file's embedded art in the item folder, e.g. ".cover-0123456789abcdef.jpg" (named by its bytes, so art shared by every
 * track is stored once).
 */
@Serializable
data class CachedTags(
    val title: String, val durationMs: Long, val rgTrackGain: Float?, val rgTrackPeak: Float?,
    val rgAlbumGain: Float?, val rgAlbumPeak: Float?, val embeddedCover: String?,
)

/** One file of a downloaded item: its local [name] in the item folder, where it came from, and how it's verified. */
@Serializable
data class ManifestFile(
    val name: String,
    val remoteName: String,
    val url: String,
    val sizeBytes: Long,
    val hash: String,
    /** A `HashAlgorithm` name in :core:downloads ("SHA1", "MD5"). */
    val hashAlgorithm: String,
    /** Archive.org's per-file title, or the LibriVox section title; "" when the origin has none. */
    val title: String = "",
    val trackNo: Int? = null,
    val readers: List<String> = emptyList(),
    val durationMs: Long? = null,
    val sectionId: String? = null,
    val tags: CachedTags? = null,
)

/**
 * The origin record of one downloaded book or album (spec §4, §10), `.granger-item.json` in its folder. Written before
 * the download is queued; the downloaded-media scanner files the item from it.
 */
@Serializable
data class MediaManifest(
    val version: Int = 1,
    /** [LIBRIVOX] or [ARCHIVE_ORG]. */
    val source: String,
    /** [AUDIOBOOK] or [ALBUM]. */
    val kind: String,
    /** The LibriVox id or the archive.org identifier. */
    val ref: String,
    val archiveIdentifier: String,
    val pageUrl: String,
    val title: String,
    val creators: List<String>,
    val readers: List<String> = emptyList(),
    val description: String = "",
    val language: String = "",
    val genres: List<String> = emptyList(),
    val date: String = "",
    val totalTimeMs: Long = 0,
    val cover: ManifestFile? = null,
    val files: List<ManifestFile>,
    val gotAt: Long,
) {
    companion object {
        const val LIBRIVOX = "librivox"
        const val ARCHIVE_ORG = "archive-org"
        const val AUDIOBOOK = "audiobook"
        const val ALBUM = "album"
    }
}

object MediaManifests {
    const val FILE_NAME = ".granger-item.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** null when missing or unreadable (torn, not JSON, not a manifest). */
    fun read(dir: File): MediaManifest? = (parse(dir) as? Parsed.Ok)?.manifest

    internal sealed interface Parsed {
        data object Missing : Parsed
        data object Unreadable : Parsed
        data class Ok(val manifest: MediaManifest) : Parsed
    }

    internal fun parse(dir: File): Parsed {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return Parsed.Missing
        return try {
            Parsed.Ok(json.decodeFromString(MediaManifest.serializer(), file.readText()))
        } catch (e: Exception) {
            Parsed.Unreadable
        }
    }

    /** Writes a temp file in [dir], syncs it, then renames it over the manifest: a reader sees the old one or the new one. */
    fun write(dir: File, manifest: MediaManifest) {
        dir.mkdirs()
        val tmp = File.createTempFile("$FILE_NAME.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(MediaManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(File(dir, FILE_NAME))) throw java.io.IOException("Couldn't replace ${File(dir, FILE_NAME)}")
        } finally {
            tmp.delete()
        }
    }

    /** "audiobooks/librivox/<id>", "audiobooks/archive-org/<identifier>", "music/archive-org/<identifier>". */
    fun relativeDir(m: MediaManifest): String {
        val shelf = if (m.kind == MediaManifest.ALBUM) "music" else "audiobooks"
        return "$shelf/${m.source}/${m.ref}"
    }

    private const val MAX_NAME = 120
    private val UNSAFE = Regex("[^A-Za-z0-9 ._,'()&!\\[\\]-]")
    private const val TRIM = " ."

    /** "<NN> - <title or remote stem>.<remote ext>", NN at least two digits, FAT-safe, ≤ 120 chars. */
    fun localName(ordinal: Int, title: String, remoteName: String): String {
        val remote = remoteName.substringAfterLast('/')
        val dot = remote.lastIndexOf('.')
        val stem = if (dot > 0) remote.substring(0, dot) else remote
        val ext = if (dot > 0) safe(remote.substring(dot + 1)).trim(*TRIM.toCharArray()).take(10) else ""
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val prefix = "%02d - ".format(ordinal + 1)
        val body = safe(title.trim().ifEmpty { stem })
            .take(MAX_NAME - prefix.length - suffix.length)
            .trim(*TRIM.toCharArray())
            .ifEmpty { "Track" }
        return prefix + body + suffix
    }

    private fun safe(s: String) = UNSAFE.replace(s, "_")

    private val SEPARATORS = Regex("[-_]+")
    private val TRAILING = charArrayOf(' ', '_', '-', ':', '.')
    private const val MIN_WORDS = 3
    private const val MIN_CHARS = 12

    /**
     * The title to show. A [title] equal to [identifier] (or blank) is a slug, and becomes the longest common prefix of
     * [fileNames] (the audio files' remote names): extensions dropped, cut back so it never ends inside a word, trailing
     * " ", "_", "-", ":", "." trimmed. The prefix is used when there are at least two names and it is at least 3 words or
     * 12 characters; otherwise the identifier reads as words, each capitalised. Any other title is returned unchanged.
     */
    fun displayTitle(title: String, identifier: String, fileNames: List<String>): String {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != identifier) return title
        commonPrefix(fileNames)?.let { return it }
        return identifier.split(SEPARATORS).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            .ifEmpty { trimmed }
    }

    /** [displayTitle] for [m]: its title, its ref, and its files' remote names (the cover not included). */
    fun displayTitle(m: MediaManifest): String = displayTitle(m.title, m.ref, m.files.map { it.remoteName })

    private fun commonPrefix(fileNames: List<String>): String? {
        if (fileNames.size < 2) return null
        val stems = fileNames.map { n -> n.substringAfterLast('/').let { if (it.lastIndexOf('.') > 0) it.substringBeforeLast('.') else it } }
        var len = stems.minOf { it.length }
        for (s in stems) len = minOf(len, stems[0].commonPrefixWith(s).length)
        // Ends inside a word when its last character and the next one in any name are both letters or digits.
        val endsInWord = len > 0 && stems[0][len - 1].isLetterOrDigit() && stems.any { it.length > len && it[len].isLetterOrDigit() }
        if (endsInWord) {
            while (len > 0 && stems[0][len - 1].isLetterOrDigit()) len--
        }
        val prefix = stems[0].substring(0, len).trimEnd(*TRAILING).trim()
        val words = prefix.split(' ').count { it.isNotBlank() }
        return prefix.takeIf { it.isNotEmpty() && (words >= MIN_WORDS || it.length >= MIN_CHARS) }
    }
}
