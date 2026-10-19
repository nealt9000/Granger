package app.granger.core.media

import java.io.File

/**
 * Remembers, across a crash, which file the scanner was reading tags from. TagLib is native: a file that makes it
 * crash (SIGSEGV) kills the process before anything is cached, so without this it would crash every scan, on every
 * launch. A file found marked at the start of a scan is the one a previous run died on, and its tags are never read
 * again (it becomes a track named from its file, as one TagLib can't parse).
 */
interface TagReadGuard {
    /** Called once at the start of a scan: a marker left by a crashed run becomes a skip. The uris never to read. */
    fun skipped(): Set<String>

    /** About to read [uri]'s tags; the mark must be on disk before this returns. */
    fun begin(uri: String)

    /** The tags were read (or failed normally): clears the mark. */
    fun end()

    companion object {
        /** Remembers nothing. */
        val NONE: TagReadGuard = object : TagReadGuard {
            override fun skipped() = emptySet<String>()
            override fun begin(uri: String) = Unit
            override fun end() = Unit
        }
    }
}

/** A [TagReadGuard] in two small files under [dir] (the app's no-backup storage). */
class FileTagReadGuard(private val dir: File) : TagReadGuard {
    private val marker = File(dir, "tags-reading")
    private val skips = File(dir, "tags-skipped")

    override fun skipped(): Set<String> = try {
        if (marker.exists()) {
            val crashed = marker.readText()
            if (crashed.isNotEmpty()) skips.appendText(crashed + "\n")
            marker.delete()
        }
        if (skips.exists()) skips.readLines().filterTo(HashSet()) { it.isNotEmpty() } else emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    override fun begin(uri: String) {
        try {
            dir.mkdirs()
            // Written straight to the file (no temp + rename): a process crash keeps what the kernel was given.
            marker.writeText(uri)
        } catch (e: Exception) {
            // Without the mark, a crash here would only repeat; the scan itself goes on.
        }
    }

    override fun end() {
        marker.delete()
    }
}
