package app.granger.archive

import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.Part
import app.granger.core.archive.TextKind
import app.granger.core.library.BookDetails
import java.io.File

// Android's File forms of the shared ArchiveDownloads' sidecars (Plan I5), as the app called them before.

/** Writes `<dir>/<identifier>.json`, or a [part]'s `<dir>/<identifier>~03.json` titled for it, and returns it. */
fun ArchiveDownloads.writeSidecar(dir: File, item: ArchiveItem, kind: TextKind, part: Part? = null, partCount: Int = 0): File {
    require(app.granger.core.archive.ArchiveQuery.isValidIdentifier(item.identifier)) { "Bad archive.org identifier" }
    dir.mkdirs()
    return File(writeSidecar(dir.path, item, kind, part, partCount))
}

/** [key] is an identifier or a part's key. */
fun ArchiveDownloads.sidecarFile(dir: File, key: String): File = File(dir, "$key.json")

/** Deletes `<dir>/<key>.json` (see the shared form). */
fun ArchiveDownloads.deleteSidecar(dir: File, key: String) {
    if (app.granger.core.archive.ArchiveQuery.isValidIdentifier(key.substringBefore(PART_SEPARATOR))) sidecarFile(dir, key).delete()
}

/** Null when the file is missing or unreadable. */
fun ArchiveDownloads.readSidecar(file: File): Pair<BookDetails, TextKind>? = readSidecar(file.path)
