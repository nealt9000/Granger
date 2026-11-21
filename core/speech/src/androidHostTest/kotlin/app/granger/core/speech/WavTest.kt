package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun monoRoundTripKeepsSamplesAndRate() {
        val samples = FloatArray(2205) { i -> kotlin.math.sin(i / 10.0).toFloat() * 0.5f }
        val file = tmp.newFile("a.wav")
        Wav.write(Pcm(samples, 22050), file)
        assertThat(file.length()).isEqualTo(44 + 2205 * 2)
        val back = Wav.read(file)
        assertThat(back.sampleRate).isEqualTo(22050)
        assertThat(back.samples.size).isEqualTo(2205)
        // Written at 1/32767 steps, read back at 1/32768: within one step either way.
        for (i in samples.indices) assertThat(back.samples[i]).isWithin(2f / 32767).of(samples[i])
        assertThat(back.durationMs).isEqualTo(100)
    }

    @Test
    fun stereoIsMixedDownAndChunksBeforeDataAreSkipped() {
        val frames = 4
        val b = ByteBuffer.allocate(44 + 8 + 4 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(8000).putInt(8000 * 4).putShort(4).putShort(16)
        b.put("LIST".toByteArray()).putInt(4).putInt(0)                     // an unrelated chunk
        b.put("data".toByteArray()).putInt(frames * 4)
        repeat(frames) { b.putShort(16384).putShort(-16384) }               // left +0.5, right -0.5
        val pcm = Wav.read(b.array())
        assertThat(pcm.sampleRate).isEqualTo(8000)
        assertThat(pcm.samples.size).isEqualTo(frames)
        assertThat(pcm.samples.all { it == 0f }).isTrue()
    }

    @Test
    fun clippingAndNonWavAreHandled() {
        val file = tmp.newFile("c.wav")
        Wav.write(Pcm(floatArrayOf(2f, -2f, 0f), 8000), file)
        val back = Wav.read(file)
        assertThat(back.samples[0]).isWithin(0.001f).of(1f)
        assertThat(back.samples[1]).isWithin(0.001f).of(-1f)
        assertThrows(IOException::class.java) { Wav.read("not a wav at all".toByteArray()) }
    }

    @Test
    fun concatJoinsChunksInOrder() {
        assertThat(Wav.concat(listOf(floatArrayOf(1f, 2f), floatArrayOf(), floatArrayOf(3f)))).usingExactEquality().containsExactly(1f, 2f, 3f).inOrder()
        assertThat(Wav.concat(emptyList())).isEmpty()
    }
}
