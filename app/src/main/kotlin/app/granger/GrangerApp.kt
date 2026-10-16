package app.granger

import android.app.Application
import android.content.Context
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibrarySearch
import app.granger.core.library.RoomLibrary
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.zim.ZimResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class GrangerApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Hand-wired dependencies; one instance per process. */
class AppContainer(context: Context) {
    /** For writes that must finish even if the screen that started them is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** `<volume>/Android/data/app.granger/files/packs` on internal storage and each SD card. */
    val packDirs: () -> List<File> = {
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "packs").apply { mkdirs() } }
    }

    val library: Library = RoomLibrary(LibraryDatabase.create(context), KiwixZimOpener(context), packDirs)
    val search: LibrarySearch = ZimLibrarySearch(library)
    val resolver = ZimResolver(library::archive)
}
