package app.granger.core.downloads

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads a [LOCAL_SCHEME] file picked by the user (Plan E1 item 4) from a byte offset: seeks where the provider gives a
 * file descriptor (the phone's storage, an SD card), else reads from the start and skips.
 */
object LocalFiles {
    fun open(context: Context, url: String, offset: Long): InputStream {
        val uri = Uri.parse(url)
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(uri, "r")
        if (pfd != null) {
            val input = FileInputStream(pfd.fileDescriptor)
            try {
                input.channel.position(offset)
            } catch (e: IOException) {
                input.close(); pfd.close(); return skipped(resolver.openInputStream(uri), offset)
            }
            return object : FilterInputStream(input) {
                override fun close() { try { super.close() } finally { pfd.close() } }
            }
        }
        return skipped(resolver.openInputStream(uri), offset)
    }

    private fun skipped(input: InputStream?, offset: Long): InputStream {
        val stream = input ?: throw IOException("Can't open the file")
        var left = offset
        while (left > 0) {
            val n = stream.skip(left)
            if (n <= 0) { stream.close(); throw IOException("The file is shorter than expected") }
            left -= n
        }
        return stream
    }
}
