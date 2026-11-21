package app.granger.core.media

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
