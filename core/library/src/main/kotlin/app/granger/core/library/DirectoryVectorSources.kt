package app.granger.core.library

import app.granger.core.research.SourceKind
import app.granger.core.vectors.VectorFile
import java.io.File

/**
 * The vector files in [dir], listed by its `index.tsv`: one line per file, `<file name>\t<ARTICLE|BOOK>\t<target>`, where
 * the target is a pack's ZIM `Name` or a book's id (`tools/vectors/push.py` writes it). Plan R1's interim source until
 * vector files are catalog downloads (Task 10). A file is opened once and kept open while its size and date stay the same;
 * a missing, damaged or unlisted file is skipped.
 */
class DirectoryVectorSources(private val dir: File) : VectorSources {
    private class Opened(val length: Long, val modified: Long, val file: VectorFile)

    private val open = HashMap<String, Opened>()

    // Nothing in here suspends: the index is a few hundred lines and opening a file maps it.
    override suspend fun current(): List<VectorSource> = synchronized(open) { load() }

    private fun load(): List<VectorSource> {
        val index = File(dir, INDEX)
        val lines = if (index.isFile) index.readLines() else emptyList()
        val listed = lines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts.any { it.isBlank() }) return@mapNotNull null
            val kind = when (parts[1]) {
                "ARTICLE" -> SourceKind.ARTICLE
                "BOOK" -> SourceKind.BOOK
                else -> return@mapNotNull null
            }
            Triple(parts[0], kind, parts[2])
        }
        val names = listed.map { it.first }.toSet()
        // Files gone from the index are closed.
        open.keys.filter { it !in names }.forEach { open.remove(it)?.file?.close() }
        return listed.mapNotNull { (name, kind, target) ->
            val f = File(dir, name)
            if (!f.isFile) return@mapNotNull null
            val cached = open[name]
            val file = if (cached != null && cached.length == f.length() && cached.modified == f.lastModified()) {
                cached.file
            } else {
                cached?.file?.close()
                val opened = VectorFile.open(f) ?: return@mapNotNull null.also { open.remove(name) }
                open[name] = Opened(f.length(), f.lastModified(), opened)
                opened
            }
            VectorSource(file, kind, target)
        }
    }

    companion object {
        const val INDEX = "index.tsv"
    }
}
