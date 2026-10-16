package app.granger.core.zim

import android.content.Context
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import java.io.File

/** Opens ZIM files with libkiwix. Constructing it loads the native libraries once per process. */
class KiwixZimOpener(context: Context) : ZimOpener {
    init {
        loadNativeLibraries(context.applicationContext)
    }

    override fun open(file: File): ZimOpenResult {
        if (!file.isFile || !file.canRead()) return ZimOpenResult.Failed("Can't read ${file.path}")
        return try {
            ZimOpenResult.Opened(KiwixZimArchive(Archive(file.absolutePath), file.nameWithoutExtension))
        } catch (e: Exception) {
            ZimOpenResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        private var loaded = false

        @Synchronized
        fun loadNativeLibraries(context: Context) {
            if (loaded) return
            JNIKiwix(context)
            loaded = true
        }
    }
}
