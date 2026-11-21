package app.granger.core.library

import app.granger.core.zim.ZimArchive
import app.granger.core.zim.copyTo
import java.io.File

/** Android's copy of a pack entry to a file, as [PackBooks] made it before Plan I5: libkiwix's own, straight to disk. */
internal fun copyOutOfPack(archive: ZimArchive, path: String, to: String): Boolean = archive.copyTo(path, File(to))
