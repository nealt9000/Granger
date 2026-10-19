package app.granger.core.media

/** One audio file a source found on the phone, before it's opened. */
data class FoundFile(
    val uri: String,
    val displayName: String,
    /** Relative path with a trailing slash, e.g. "Audiobooks/Dune/". */
    val folder: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    /** From the source (MediaStore's DURATION); 0 if unknown. TagLib's own value wins. */
    val durationMs: Long,
    /** MediaStore's IS_AUDIOBOOK. */
    val systemAudiobook: Boolean,
    /** In a folder the user added as an audiobook folder. */
    val inAudiobookFolder: Boolean,
    /** `folder.jpg` or `cover.jpg` beside the file, where the source can see it (added folders); null otherwise. */
    val folderCoverUri: String? = null,
)

/** Lists audio files. Throws when it can't tell what's there, so a failure is never mistaken for an empty source. */
interface AudioSource {
    suspend fun list(): List<FoundFile>
}
