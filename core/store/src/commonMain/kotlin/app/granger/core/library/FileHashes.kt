package app.granger.core.library

/** A file's SHA-256 for code outside the store (Plan I5 Task 7: the online bookshelf's checks), streamed in 64 KB pieces. */
object FileHashes {
    /** [path]'s SHA-256 in lower-case hex. Throws `IOException` when it can't be read. */
    fun sha256(path: String): String = Disk.source(path).use { sha256(it) }
}
