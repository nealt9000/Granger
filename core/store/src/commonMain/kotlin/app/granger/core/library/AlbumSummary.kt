package app.granger.core.library

/**
 * An album as the library shows it, built from its present tracks (MISSING ones aren't part of it). [title] is
 * [Albums.title] (an untagged album is named after its folder) and [albumArtist] carries the "Unknown artist" fallback. [status] is OK while any track is OK.
 */
data class AlbumSummary(
    val key: String,
    val title: String,
    val albumArtist: String,
    val year: String,
    val releaseType: String,
    val trackCount: Int,
    val durationMs: Long,
    val coverPath: String?,
    val status: PackStatus,
)
