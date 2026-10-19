package app.granger.core.media

import android.os.ParcelFileDescriptor
import app.granger.core.taglib.RawTags
import app.granger.core.taglib.TagLib
import java.io.FileInputStream

/** What one file's bytes say: TagLib's tags (null if TagLib can't parse the file) and its chapters. */
data class FileTags(val raw: RawTags?, val chapters: List<Chapter>)

/** Reads one opened file's tags. Throws only when the bytes can't be read at all; the scanner then skips the file. */
fun interface TagReader {
    fun read(pfd: ParcelFileDescriptor, file: FoundFile): FileTags
}

/** TagLib (native) for tags and the embedded cover, [ChapterReader] for chapters. Both only read. */
object NativeTagReader : TagReader {
    override fun read(pfd: ParcelFileDescriptor, file: FoundFile): FileTags {
        val raw = TagLib.read(pfd.fd, file.displayName)
        // Not closed: closing a stream made from a FileDescriptor closes the fd, which the caller owns.
        val chapters = ChapterReader.read(FileInputStream(pfd.fileDescriptor).channel)
        return FileTags(raw, chapters)
    }
}
