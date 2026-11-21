package app.granger.core.downloads

data class DownloadRequest(
    val urls: List<String>,
    val sizeBytes: Long,
    val hash: String,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA256,
    /** Wi-Fi only (Plan I4): the iPhone's transfer then never uses mobile data; Android's NetworkGate checks it itself. */
    val wifiOnly: Boolean = false,
)

sealed interface DownloadResult {
    data object Done : DownloadResult

    /** The bytes didn't match the expected checksum. The part file has been deleted. */
    data object HashMismatch : DownloadResult

    /** Writing on would leave less than the reserve free. The bytes so far are kept. */
    data class NoSpace(val neededBytes: Long) : DownloadResult

    /**
     * Every URL failed; [reason] is the last error. The bytes so far are kept for a resume.
     * [permanent] means every URL failed in a way retrying won't fix (gone, a different file, a bad address).
     * [gone] means every URL answered 404 or 410: the file has been withdrawn (Plan DL Decision 10).
     */
    data class Failed(val reason: String, val permanent: Boolean = false, val gone: Boolean = false) : DownloadResult
}

/**
 * Fetches one file into its `.part` file, resuming what is there, with a streaming checksum and a free-space check
 * (Plan I4 Task 1). Android's is [FileDownloader] in :core:downloads (OkHttp); the iPhone's is a URLSession transfer.
 * It never renames or registers anything: [DownloadRunner] does that once the checksum has passed.
 */
interface FileTransfer {
    /** [onProgress] gets the bytes in the part file so far, first what an earlier run left. */
    suspend fun download(request: DownloadRequest, partPath: String, onProgress: (Long) -> Unit = {}): DownloadResult

    companion object {
        /** Never fill a volume past this, whatever the queue check allowed. */
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
    }
}
