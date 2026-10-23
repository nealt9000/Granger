package app.granger.core.library

import app.granger.core.research.SourceKind
import app.granger.core.text.Chunkers
import app.granger.core.vectors.VectorFile
import java.io.File

/** A vector file and what it belongs to, before it is opened; see [VectorSource]. */
data class ListedVectors(val file: File, val kind: SourceKind, val target: String)

/**
 * The vector files [listing] names (Plan R1 Task 10: the installed vectors downloads). A file is opened once and kept open
 * while its size and date stay the same; a missing, damaged or unlisted one is skipped, and so is one cut by another
 * chunker or embedded by another embedder than [embedder]'s sha256 (null when it isn't known: then any is used).
 */
class ListedVectorSources(
    private val listing: suspend () -> List<ListedVectors>,
    private val embedder: suspend () -> String? = { null },
) : VectorSources {
    private val cache = VectorFileCache()

    override suspend fun current(): List<VectorSource> {
        val listed = listing()
        val sha = embedder()
        return cache.open(listed).filter { sha == null || it.file.header.embedderSha256 == sha }
    }
}

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

/** Every source of [parts], in order; a target listed twice keeps its first file (the catalog's over a pushed one). */
class CombinedVectorSources(private vararg val parts: VectorSources) : VectorSources {
    override suspend fun current(): List<VectorSource> {
        val seen = HashSet<Pair<SourceKind, String>>()
        return parts.flatMap { it.current() }.filter { seen.add(it.kind to it.target) }
    }
}

/** The open files behind a listing, each kept open while its size and date stay the same. */
private class VectorFileCache {
    private class Opened(val length: Long, val modified: Long, val file: VectorFile)

    private val open = HashMap<String, Opened>()

    fun open(listed: List<ListedVectors>): List<VectorSource> = synchronized(open) {
        val paths = listed.map { it.file.path }.toSet()
        // Files gone from the listing are closed.
        open.keys.filter { it !in paths }.forEach { open.remove(it)?.file?.close() }
        listed.mapNotNull { (f, kind, target) ->
            if (!f.isFile) return@mapNotNull null
            val cached = open[f.path]
            val file = if (cached != null && cached.length == f.length() && cached.modified == f.lastModified()) {
                cached.file
            } else {
                cached?.file?.close()
                val opened = VectorFile.open(f) ?: return@mapNotNull null.also { open.remove(f.path) }
                open[f.path] = Opened(f.length(), f.lastModified(), opened)
                opened
            }
            // Chunks cut another way can't be rebuilt into passages.
            if (file.header.chunkerVersion != Chunkers.VERSION) null else VectorSource(file, kind, target)
        }
    }
}
