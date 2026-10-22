package app.granger.core.vectors

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A test-only writer of the gvec layout (the real writer is `tools/vectors/gvec.py`). */
object TestGvec {
    fun write(file: File, vectors: List<FloatArray>, keys: List<ChunkKey> = vectors.indices.map { ChunkKey("A/$it", 0) }): File {
        val dims = vectors.firstOrNull()?.size ?: 4
        val blob = java.io.ByteArrayOutputStream()
        val offsets = keys.map { k ->
            val at = blob.size()
            val bytes = k.key.toByteArray()
            blob.write(ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt(k.ordinal).putShort(bytes.size.toShort()).array())
            blob.write(bytes)
            at
        }
        val count = vectors.size
        val keysAt = 128L + count.toLong() * (dims + 12)
        val buf = ByteBuffer.allocate((keysAt + 4L * count + 4 + blob.size()).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GVEC".toByteArray()).putShort(1).putShort(1).putInt(dims).putInt(count)
        repeat(64) { buf.put(0) }
        buf.putLong(keysAt)
        buf.position(128)
        val quantized = vectors.map { VectorSearch.quantize(it, dims) }
        quantized.forEach { buf.put(it.first) }
        quantized.forEach { buf.putFloat(it.second) }
        repeat(count) { buf.putLong(0) }
        offsets.forEach { buf.putInt(it) }
        buf.putInt(blob.size()).put(blob.toByteArray())
        file.writeBytes(buf.array())
        return file
    }
}
