package app.granger.core.vectors

import java.io.File
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** The JVM side's `File`-based calls, kept for Android and the Mac tools (Plan I1). */
val VectorFile.file: File get() = File(path)

fun VectorFile.Companion.open(file: File): VectorFile? = open(file.path)

fun VectorFile.Companion.write(
    file: File,
    vectors: List<FloatArray>,
    dims: Int,
    hashes: List<String>,
    keys: List<ChunkKey>,
    chunkerVersion: Int,
    embedderSha256: String,
    sourceSha256: String,
) = write(file.path, vectors, dims, hashes, keys, chunkerVersion, embedderSha256, sourceSha256)

/** The blocking form Android's callers use, its tasks on [pool] as before. */
fun VectorSearch.top(query: FloatArray, files: List<VectorFile>, k: Int, pool: ExecutorService, probes: Int? = null): List<VectorHit> =
    runBlocking { top(query, files, k, pool.asCoroutineDispatcher(), probes) }
