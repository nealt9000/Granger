package app.granger.core.media

import java.io.File

/** Deletes phone covers no row names any more (they'd otherwise pile up in `media-covers` forever). */
object CoverSweep {
    /** Deletes every `*.jpg` directly in [coversDir] whose path isn't in [referenced]; returns how many were deleted. */
    fun sweep(coversDir: File, referenced: Set<String>): Int {
        val names = referenced.mapTo(HashSet()) { File(it).absolutePath }
        val files = coversDir.listFiles() ?: return 0
        return files.count { f -> f.isFile && f.name.endsWith(".jpg") && f.absolutePath !in names && f.delete() }
    }
}
