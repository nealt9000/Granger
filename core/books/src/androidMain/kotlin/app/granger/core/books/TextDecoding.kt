package app.granger.core.books

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object TextDecoding {
    fun decode(bytes: ByteArray): String {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return strictUtf8(bytes, 3) ?: cp1252(bytes, 3)
        if (at(0) == 0xFF && at(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (at(0) == 0xFE && at(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return strictUtf8(bytes, 0) ?: cp1252(bytes, 0)
    }

    private fun cp1252(bytes: ByteArray, from: Int) = String(bytes, from, bytes.size - from, charset("windows-1252"))

    private fun strictUtf8(bytes: ByteArray, from: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
