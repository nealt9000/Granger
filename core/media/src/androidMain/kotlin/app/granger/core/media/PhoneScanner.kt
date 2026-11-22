package app.granger.core.media

import android.os.ParcelFileDescriptor
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PlaybackDao
import java.io.File
import java.io.FileInputStream

/**
 * Scans the phone's music and audiobooks into [MediaLibrary], referenced in place: the shared [PhoneMediaScan] (Plan I6
 * Task 6) over Android's files. Every file is opened through [open], which opens read-only ([ContentResolverOpener]);
 * its fingerprint is read from the descriptor and its tags by [tagReader] (TagLib over JNI, [ChapterReader]). Covers
 * are copied into [coversDir] and swept by [CoverSweep]. See [PhoneMediaScan] for the rules.
 */
class PhoneScanner(
    sources: List<AudioSource>,
    open: (String) -> ParcelFileDescriptor?,
    media: MediaLibrary,
    playback: PlaybackDao,
    coversDir: File,
    hasPermission: () -> Boolean,
    tagReader: TagReader = NativeTagReader,
    tagGuard: TagReadGuard = TagReadGuard.NONE,
) : PhoneMediaScan(sources, DescriptorFiles(open, tagReader), media, playback, coversDir.path, hasPermission, tagGuard) {
    override fun sweep(dir: String, referenced: Set<String>) {
        CoverSweep.sweep(File(dir), referenced)
    }
}

/** Android's files for the shared scanner: opened read-only through a content resolver, as before Plan I6. */
private class DescriptorFiles(private val open: (String) -> ParcelFileDescriptor?, private val tagReader: TagReader) : PhoneFiles {
    override fun <T> open(file: FoundFile, use: (OpenedFile) -> T): T? {
        val pfd = try {
            open(file.uri)
        } catch (e: Exception) {
            null
        } ?: return null
        return pfd.use {
            use(object : OpenedFile {
                // Not closed: closing a stream made from a FileDescriptor would close the fd under pfd.
                override fun fingerprint(): String = Fingerprint.of(FileInputStream(it.fileDescriptor).channel)
                override fun tags(): FileTags = tagReader.read(it, file)
            })
        }
    }

    override fun readBytes(uri: String): ByteArray? = open(uri)?.use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
}
