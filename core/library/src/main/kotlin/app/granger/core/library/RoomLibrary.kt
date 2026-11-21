package app.granger.core.library

import app.granger.core.zim.ZimOpener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * [Library] backed by Room: the shared [PackLibrary] (Plan I4 Task 3) over Android's `File`s and java-libkiwix
 * [ZimOpener]. Packs are the `*.zim` files in [packDirs], one `packs` folder per external-files volume.
 */
class RoomLibrary(
    db: LibraryDatabase,
    opener: ZimOpener,
    packDirs: () -> List<File>,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    /**
     * Runs after a pack is removed or superseded, with its row: its pack documents (Plan P2) go too, unless a pack of
     * the same name continues them (Plan KB2 Task 2.4).
     */
    onRemoved: suspend (pack: Pack) -> Unit = {},
) : PackLibrary(db, { path -> opener.open(File(path)) }, { packDirs().map { it.absolutePath } }, io, now, onRemoved)
