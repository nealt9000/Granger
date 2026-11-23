package app.granger.core.library

import app.granger.core.research.SourceKind
import java.io.File

/** A vector file and what it belongs to, before it is opened, by [File] (the shared [ListedVectors] takes its path). */
fun ListedVectors(file: File, kind: SourceKind, target: String): ListedVectors = ListedVectors(file.path, kind, target)

/**
 * The vector files in [dir], listed by its `index.tsv`: one line per file, `<file name>\t<ARTICLE|BOOK>\t<target>`, where
 * the target is a pack's ZIM `Name` or a book's id (`tools/vectors/push.py` writes it). Plan R1's interim source, kept
 * for the eval's adb-pushed files beside the catalog downloads ([ListedVectorSources]). Opened as that one is.
 */
class DirectoryVectorSources(private val dir: File) : VectorSources {
    private val cache = VectorFileCache()

    // Nothing in here suspends: the index is a few hundred lines and opening a file maps it.
    override suspend fun current(): List<VectorSource> = cache.open(listing())

    private fun listing(): List<ListedVectors> {
        val index = File(dir, INDEX)
        val lines = if (index.isFile) index.readLines() else emptyList()
        return lines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts.any { it.isBlank() }) return@mapNotNull null
            val kind = when (parts[1]) {
                "ARTICLE" -> SourceKind.ARTICLE
                "BOOK" -> SourceKind.BOOK
                else -> return@mapNotNull null
            }
            ListedVectors(File(dir, parts[0]), kind, parts[2])
        }
    }

    companion object {
        const val INDEX = "index.tsv"
    }
}
