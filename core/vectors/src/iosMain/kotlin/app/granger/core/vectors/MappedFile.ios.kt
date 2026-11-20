package app.granger.core.vectors

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
actual class MappedFile(private val base: CPointer<ByteVar>, actual val size: Long) : AutoCloseable {
    actual fun byte(at: Long): Byte = base[at]
    actual fun short(at: Long): Int = (byte(at).toInt() and 0xFF) or ((byte(at + 1).toInt() and 0xFF) shl 8)
    actual fun int(at: Long): Int = short(at) or (short(at + 2) shl 16)
    actual fun long(at: Long): Long = (int(at).toLong() and 0xFFFFFFFFL) or (int(at + 4).toLong() shl 32)
    actual fun float(at: Long): Float = Float.fromBits(int(at))
    actual fun copy(at: Long, dst: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        dst.usePinned { memcpy(it.addressOf(offset), base + at, length.convert()) }
    }
    actual override fun close() { munmap(base, size.convert()) }
}

@OptIn(ExperimentalForeignApi::class)
actual fun mapReadOnly(path: String): MappedFile? = memScoped {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    try {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) return null
        val size = st.st_size
        if (size == 0L) return null
        val p = mmap(null, size.convert(), PROT_READ, MAP_PRIVATE, fd, 0) ?: return null
        if (p == MAP_FAILED) return null
        MappedFile(p.reinterpret(), size)
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeFileAtomically(path: String, bytes: ByteArray) {
    val tmp = "$path.part"
    val f = fopen(tmp, "wb") ?: throw IllegalStateException("could not open $tmp")
    val wrote = if (bytes.isEmpty()) 0uL else bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f) }
    fclose(f)
    if (wrote.toLong() != bytes.size.toLong() || rename(tmp, path) != 0) throw IllegalStateException("could not write $path")
}
