package app.granger.core.archive

data class ArchiveDoc(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: Int?,
    val formats: List<String>,
    val restricted: Boolean,
    val downloads: Long,
    val mediatype: String = "",
)

data class SearchPage(val total: Int, val docs: List<ArchiveDoc>)

data class ArchiveFile(
    val name: String,
    val format: String,
    val sizeBytes: Long?,
    val sha1: String?,
    val md5: String?,
    val source: String,
    /** archive.org's `private` flag (JSON key "private"): such files are never downloaded. */
    val isPrivate: Boolean,
    /** archive.org's per-file `title`, `album` and `creator`, "" when absent (only some audio files carry them). */
    val title: String = "",
    val album: String = "",
    val creator: String = "",
    /** The leading number of `track` ("01", "7", "1/13"), else null. */
    val track: Int? = null,
    /** `length` as seconds ("373.16") or a clock ("19:32", "1:02:03"), in milliseconds, else null. */
    val lengthMs: Long? = null,
)

data class ArchiveItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val date: String,
    val year: Int?,
    val subjects: List<String>,
    val description: String,
    val language: String,
    val licenseUrl: String,
    val restricted: Boolean,
    val files: List<ArchiveFile>,
    val mediatype: String = "",
    /** `collection`, which archive.org sends as a string or a list. */
    val collections: List<String> = emptyList(),
)

class ArchiveError(message: String) : Exception(message)
