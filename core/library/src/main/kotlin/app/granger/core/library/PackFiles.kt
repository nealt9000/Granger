package app.granger.core.library

import app.granger.core.zim.ZimArchive

/** A document inside a website pack (Plan KB2 Task 4.6): its entry [path], a [title] from its file name, its type and size. */
data class PackFile(val path: String, val title: String, val mimeType: String, val sizeBytes: Long)

/**
 * Files in this pack (Plan KB2 Task 4.6, design §4.7): the PDFs and EPUBs a website pack holds (Ready.gov's info sheets,
 * Wikibooks' printable books), listed so they can be found without hunting for links.
 */
object PackFiles {
    /** How many PDFs and EPUBs a ZIM's `Counter` metadata says it holds: the cheap check before any listing. */
    fun count(counter: String): Int = BookFiles.parseCounter(counter).filterKeys { it in BookFiles.MIMES }.values.sum()

    /**
     * [archive]'s PDFs and EPUBs (`ZimArchive.list`), titled from their file names (`BookFiles.titleOf`), A–Z by title;
     * null when the archive can't be listed. Walks the whole ZIM, so off the main thread.
     */
    fun list(archive: ZimArchive): List<PackFile>? {
        val out = ArrayList<PackFile>()
        val walked = archive.list(BookFiles.MIMES) { e ->
            out += PackFile(e.path, BookFiles.titleOf(e.path), e.mimeType, e.sizeBytes)
            true
        }
        if (!walked) return null
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
