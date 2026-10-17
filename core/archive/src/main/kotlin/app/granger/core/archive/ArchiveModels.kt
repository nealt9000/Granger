package app.granger.core.archive

data class ArchiveDoc(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: Int?,
    val formats: List<String>,
    val restricted: Boolean,
    val downloads: Long,
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
)

class ArchiveError(message: String) : Exception(message)
