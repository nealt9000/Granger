package app.granger.core.vectors

import java.io.File

/** Writes a test vector file with blank hashes and shas. */
object TestGvec {
    fun write(file: File, vectors: List<FloatArray>, keys: List<ChunkKey> = vectors.indices.map { ChunkKey("A/$it", 0) }): File {
        val dims = vectors.firstOrNull()?.size ?: 4
        VectorFile.write(file, vectors, dims, vectors.map { "0".repeat(16) }, keys, 1, "0".repeat(64), "0".repeat(64))
        return file
    }
}
