package app.granger.core.vectors

/**
 * A file mapped read-only (JVM: `FileChannel.map`; iOS: `mmap`). Multi-byte reads are little-endian. Offsets are
 * Longs, but the JVM's mapping is one `ByteBuffer`, so a file over 2 GB isn't readable there (as before I1).
 */
expect class MappedFile : AutoCloseable {
    val size: Long
    fun byte(at: Long): Byte
    /** An unsigned 16-bit value. */
    fun short(at: Long): Int
    fun int(at: Long): Int
    fun long(at: Long): Long
    fun float(at: Long): Float
    fun copy(at: Long, dst: ByteArray, offset: Int, length: Int)
    override fun close()
}

/** Null when [path] can't be opened or mapped (or is empty). */
expect fun mapReadOnly(path: String): MappedFile?

/** Writes [bytes] to `path.part`, then moves it into place. Throws on failure. */
expect fun writeFileAtomically(path: String, bytes: ByteArray)

/** A little-endian byte builder for the writer. */
internal class LeBytes(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    var size = 0
        private set

    private fun room(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
    fun put(b: ByteArray) = apply { room(b.size); b.copyInto(buf, size); size += b.size }
    fun short(v: Int) = apply { room(2); buf[size++] = v.toByte(); buf[size++] = (v shr 8).toByte() }
    fun int(v: Int) = apply { short(v); short(v ushr 16) }
    fun long(v: Long) = apply { int(v.toInt()); int((v ushr 32).toInt()) }
    fun float(v: Float) = int(v.toBits())
    fun bytes(): ByteArray = buf.copyOf(size)
}

private const val DIGITS = "0123456789abcdef"

internal fun hexOf(map: MappedFile, at: Long, bytes: Int): String = buildString(2 * bytes) {
    for (i in 0 until bytes) {
        val v = map.byte(at + i).toInt() and 0xFF
        append(DIGITS[v shr 4]).append(DIGITS[v and 0xF])
    }
}
