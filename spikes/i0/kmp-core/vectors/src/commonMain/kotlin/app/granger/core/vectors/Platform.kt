package app.granger.core.vectors

/** A file mapped read-only; multi-byte reads are little-endian. Null from [mapReadOnly] when the file can't be opened. */
expect class MappedFile : AutoCloseable {
    val size: Long
    fun byte(at: Long): Byte
    fun copy(at: Long, dst: ByteArray, offset: Int, length: Int)
    override fun close()
}

expect fun mapReadOnly(path: String): MappedFile?

/** Writes [bytes] to `path.part`, then moves it into place. Throws on failure. */
expect fun writeFileAtomically(path: String, bytes: ByteArray)

fun MappedFile.short(at: Long): Int = (byte(at).toInt() and 0xFF) or ((byte(at + 1).toInt() and 0xFF) shl 8)
fun MappedFile.int(at: Long): Int = short(at) or (short(at + 2) shl 16)
fun MappedFile.long(at: Long): Long = (int(at).toLong() and 0xFFFFFFFFL) or (int(at + 4).toLong() shl 32)
fun MappedFile.float(at: Long): Float = Float.fromBits(int(at))

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
