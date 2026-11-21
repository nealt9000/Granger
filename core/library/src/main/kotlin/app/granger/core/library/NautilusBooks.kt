package app.granger.core.library

import app.granger.core.zim.ZimArchive
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Android's [NautilusPackBooks]: the `File`s and libkiwix copy it was built with before Plan I5. */
class NautilusBooks(
    db: LibraryDatabase,
    archiveOf: (packId: String) -> ZimArchive?,
    dir: () -> File,
    coversDir: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    pdfCovers: PdfCovers = PlatformPdfCovers,
) : NautilusPackBooks(db, archiveOf, { dir().absolutePath }, coversDir.absolutePath, io, now, pdfCovers, ::copyOutOfPack)
