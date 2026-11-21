package app.granger.core.media

import app.granger.core.taglib.RawTags

/** What one file's bytes say: its tags (null if they can't be parsed) and its chapters. */
data class FileTags(val raw: RawTags?, val chapters: List<Chapter>)

/**
 * Reads one file's tags by its path, read-only (Plan I6 Task 1: the shared scanner's form). Android opens the file and
 * hands it to its TagReader (TagLib); the iPhone reads AVAsset metadata.
 *
 * The reader opens the file first (a file that can't be opened throws before [guard] is touched), then calls
 * `guard.begin(file.uri)`, reads, and calls `guard.end()` after a return or an `Exception`; an `Error` (the stand-in for
 * a native crash) leaves the mark for the next scan. Throws only when the bytes can't be read at all.
 */
fun interface PathTagReader {
    fun read(path: String, file: FoundFile, guard: TagReadGuard): FileTags
}
