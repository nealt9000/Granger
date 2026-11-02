package app.granger.core.media

import java.io.File

/**
 * Remembers, across a crash, which file the scanner was reading tags from. TagLib is native: a file that makes it
 * crash (SIGSEGV) kills the process before anything is cached, so without this it would crash every scan, on every
 * launch. A file found marked at the start of a scan is one a previous run died on. The first time, it is only
 * suspect and read again, since any process death mid-read (swiped away, killed for memory, an update) leaves the
 * same mark. Found marked a second time, its tags are never read again (it becomes a track named from its file, as
 * one TagLib can't parse). A suspect read cleanly is cleared.
 */
interface TagReadGuard {
    /** Called once at the start of a scan: a marker left by a dead run becomes a strike. The uris never to read. */
    fun skipped(): Set<String>

    /** About to read [uri]'s tags; the mark must be on disk before this returns. */
    fun begin(uri: String)

    /** The tags were read (or failed normally): clears the mark, and the file's strike if it had one. */
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

/**
 * A [TagReadGuard] in small files under [dir] (the app's no-backup storage). The skip list moved to `tags-skipped-2`
 * with the second strike (2026-11-02): an entry in the old `tags-skipped`, made on one death, starts as a suspect.
 */
class FileTagReadGuard(private val dir: File) : TagReadGuard {
    private val marker = File(dir, "tags-reading")
    private val suspects = File(dir, "tags-suspect")
    private val skips = File(dir, "tags-skipped-2")
    private val oneStrikeSkips = File(dir, "tags-skipped")
    private var reading: String? = null
    private var suspect: MutableSet<String> = HashSet()

    override fun skipped(): Set<String> = try {
        suspect = lines(suspects)
        if (oneStrikeSkips.exists()) {
            suspect += lines(oneStrikeSkips)
            write(suspects, suspect)
            oneStrikeSkips.delete()
        }
        if (marker.exists()) {
            val died = marker.readText()
            if (died.isNotEmpty()) {
                if (suspect.remove(died)) {
                    skips.appendText(died + "\n")
                } else {
                    suspect += died
                }
                write(suspects, suspect)
            }
            marker.delete()
        }
        lines(skips)
    } catch (e: Exception) {
        emptySet()
    }

    override fun begin(uri: String) {
        reading = uri
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
        val uri = reading ?: return
        reading = null
        if (suspect.remove(uri)) {
            try { write(suspects, suspect) } catch (e: Exception) { /* it stays suspect: one more read, no harm */ }
        }
    }

    private fun lines(f: File): MutableSet<String> =
        if (f.exists()) f.readLines().filterTo(HashSet()) { it.isNotEmpty() } else HashSet()

    private fun write(f: File, uris: Set<String>) {
        if (uris.isEmpty()) f.delete() else { dir.mkdirs(); f.writeText(uris.joinToString("\n", postfix = "\n")) }
    }
}
