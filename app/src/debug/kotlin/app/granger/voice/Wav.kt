package app.granger.voice

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio as the spike passes it around: mono float samples in [-1, 1]. */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = if (sampleRate <= 0) 0L else samples.size * 1000L / sampleRate
}

/** 16-bit PCM WAV files: written for the listening session, read back from the system engine's output. */
object Wav {
    fun write(pcm: Pcm, file: File) {
        val dataBytes = pcm.samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes).put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        for (s in pcm.samples) buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        file.writeBytes(buffer.array())
    }

    /** Reads a 16-bit PCM WAV; stereo is mixed down to mono. */
    fun read(file: File): Pcm = read(file.readBytes())

    fun read(bytes: ByteArray): Pcm {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Not a WAV file")
        }
        var pos = 12
        var channels = 1
        var sampleRate = 0
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val format = b.getShort(body).toInt()
                    channels = b.getShort(body + 2).toInt()
                    sampleRate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    if (format != 1 || bits != 16) throw IOException("Only 16-bit PCM is read (format $format, $bits bits)")
                }
                "data" -> {
                    // Some writers leave the size as 0 or too big when streaming; trust the file's end then.
                    val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                    val frames = (end - body) / (2 * channels)
                    val out = FloatArray(frames)
                    var p = body
                    for (i in 0 until frames) {
                        var sum = 0f
                        repeat(channels) { sum += b.getShort(p) / 32768f; p += 2 }
                        out[i] = sum / channels
                    }
                    if (sampleRate <= 0) throw IOException("No fmt chunk before data")
                    return Pcm(out, sampleRate)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IOException("No data chunk")
    }

    /** Joins chunks into one array. */
    fun concat(chunks: List<FloatArray>): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }
}
