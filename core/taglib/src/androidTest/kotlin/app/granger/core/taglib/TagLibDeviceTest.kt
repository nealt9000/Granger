package app.granger.core.taglib

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagLibDeviceTest {

    /** Copies a fixture from the test APK's assets (testdata/audio) to the cache dir and reads it read-only. */
    private fun read(name: String): RawTags? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            TagLib.read(pfd.fd, name)
        }
    }

    @Test fun readsMp3Properties() {
        val t = read("tagged.mp3")!!
        assertThat(t.properties["TITLE"]).containsExactly("Mars, the Bringer of War")
        assertThat(t.properties["ALBUMARTIST"]).containsExactly("Gustav Holst")
        assertThat(t.properties["ARTIST"]).containsExactly("London Symphony Orchestra", "Gustav Holst")
        assertThat(t.properties["REPLAYGAIN_TRACK_GAIN"]).containsExactly("-6.50 dB")
        assertThat(t.durationMs).isIn(Range.closed(1900L, 3100L))
        assertThat(t.cover).isNotNull()
    }

    @Test fun readsEveryFormat() {
        listOf("tagged.flac", "tagged.ogg", "tagged.opus", "tagged.m4a").forEach {
            assertThat(read(it)!!.properties["TITLE"]).isNotEmpty()
        }
    }

    @Test fun opusKeepsR128Gain() {
        assertThat(read("tagged.opus")!!.properties["R128_TRACK_GAIN"]).isNotEmpty()
    }

    @Test fun untaggedFileReadsWithEmptyProperties() {
        assertThat(read("untagged.mp3")!!.properties).isEmpty()
    }

    @Test fun brokenFileReturnsNull() {
        assertThat(read("broken.mp3")).isNull()
    }
}
