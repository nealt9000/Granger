package app.granger.core.library

import java.util.zip.CRC32
import java.util.zip.Inflater

internal actual fun crc32(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

internal actual fun zlibInflate(bytes: ByteArray, at: Int, len: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(bytes, at, len)
        val out = java.io.ByteArrayOutputStream(len * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val k = inflater.inflate(buf)
            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, k)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
