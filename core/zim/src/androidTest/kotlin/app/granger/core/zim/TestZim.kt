package app.granger.core.zim

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Copies testdata/granger-test.zim out of the test APK's assets into the app's cache dir. */
object TestZim {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun copy(name: String = "granger-test.zim"): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open("granger-test.zim").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    fun open(): ZimArchive = (KiwixZimOpener(context).open(copy()) as ZimOpenResult.Opened).archive
}
