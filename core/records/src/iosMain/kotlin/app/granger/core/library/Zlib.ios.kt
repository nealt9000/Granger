package app.granger.core.library

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual fun crc32(bytes: ByteArray): Int {
    if (bytes.isEmpty()) return 0
    return bytes.usePinned { platform.zlib.crc32(0u, it.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert()).toInt() }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun zlibInflate(bytes: ByteArray, at: Int, len: Int): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    var out = ByteArray(maxOf(len * 4, 1024))
    var produced = 0
    bytes.usePinned { input ->
        stream.next_in = input.addressOf(at).reinterpret()
        stream.avail_in = len.convert()
        check(inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().convert()) == Z_OK) { "inflateInit" }
        try {
            while (true) {
                if (produced == out.size) out = out.copyOf(out.size * 2)
                val status = out.usePinned { o ->
                    stream.next_out = o.addressOf(produced).reinterpret()
                    stream.avail_out = (out.size - produced).convert()
                    val r = inflate(stream.ptr, Z_NO_FLUSH)
                    produced = out.size - stream.avail_out.toInt()
                    r
                }
                if (status == Z_STREAM_END) break
                if (status != Z_OK) break
                if (stream.avail_in.toInt() == 0 && stream.avail_out.toInt() > 0) break
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
    out.copyOf(produced)
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <reified T : kotlinx.cinterop.CVariable> sizeOf(): Long = kotlinx.cinterop.sizeOf<T>()
