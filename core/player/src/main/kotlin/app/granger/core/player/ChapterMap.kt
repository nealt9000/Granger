package app.granger.core.player

import app.granger.core.library.AudiobookChapter

/**
 * An audiobook's chapters against its player playlist. Every file of the book is one playlist item, in the order its
 * chapters first name it ([files]); a chapter is (file, startMs within that file). All chapter lookups (seek to
 * chapter, the chapter shown, previous/next chapter, end of chapter for the sleep timer, bookmarks and addresses) go
 * through here. [chapters] must be non-empty, in ordinal order.
 */
internal class ChapterMap(val chapters: List<AudiobookChapter>) {
    init { require(chapters.isNotEmpty()) { "An audiobook needs at least one chapter" } }

    data class At(val fileIndex: Int, val positionMs: Long)

    val files: List<String> = chapters.map { it.trackId }.distinct()
    private val fileOf = chapters.map { files.indexOf(it.trackId) }

    val size: Int get() = chapters.size

    /** List index of the chapter with [ordinal], clamped into range. */
    fun indexOf(ordinal: Int): Int = chapters.indexOfFirst { it.ordinal == ordinal }.takeIf { it >= 0 }
        ?: if (ordinal < chapters.first().ordinal) 0 else chapters.lastIndex

    fun start(ordinal: Int): At = indexOf(ordinal).let { At(fileOf[it], chapters[it].startMs) }

    /** The chapter playing at [positionMs] into file [fileIndex]: the last one starting at or before it. */
    fun chapterAt(fileIndex: Int, positionMs: Long): AudiobookChapter {
        var found = 0
        for (i in chapters.indices) {
            val f = fileOf[i]
            if (f < fileIndex || (f == fileIndex && chapters[i].startMs <= positionMs)) found = i else if (f > fileIndex) break
        }
        return chapters[found]
    }

    /** Milliseconds into the chapter playing at that point. */
    fun inChapter(fileIndex: Int, positionMs: Long): Long {
        val c = chapterAt(fileIndex, positionMs)
        return if (fileOf[chapters.indexOf(c)] == fileIndex) (positionMs - c.startMs).coerceAtLeast(0L) else 0L
    }

    fun fileIndexOf(chapter: AudiobookChapter): Int = fileOf[chapters.indexOf(chapter)]
}
