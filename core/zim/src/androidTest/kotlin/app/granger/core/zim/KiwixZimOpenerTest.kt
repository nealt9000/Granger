package app.granger.core.zim

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class KiwixZimOpenerTest {
    private val opener = KiwixZimOpener(TestZim.context)

    @Test fun opensTheTestZim() {
        val result = opener.open(TestZim.copy())
        assertThat(result).isInstanceOf(ZimOpenResult.Opened::class.java)
        (result as ZimOpenResult.Opened).archive.close()
    }

    @Test fun missingFileFails() {
        assertThat(opener.open(File(TestZim.context.cacheDir, "nope.zim")))
            .isInstanceOf(ZimOpenResult.Failed::class.java)
    }

    @Test fun garbageFileFails() {
        val file = File(TestZim.context.cacheDir, "garbage.zim").apply { writeBytes(Random(1).nextBytes(64 * 1024)) }
        assertThat(opener.open(file)).isInstanceOf(ZimOpenResult.Failed::class.java)
    }

    @Test fun emptyFileFails() {
        val file = File(TestZim.context.cacheDir, "empty.zim").apply { writeBytes(ByteArray(0)) }
        assertThat(opener.open(file)).isInstanceOf(ZimOpenResult.Failed::class.java)
    }

    @Test fun truncatedFileNeverCrashes() {
        // An interrupted download: a valid header with most of the file missing. libzim may refuse it
        // outright or open it and fail on reads; either is fine as long as nothing throws or crashes.
        val full = TestZim.copy().readBytes()
        val file = File(TestZim.context.cacheDir, "truncated.zim").apply { writeBytes(full.copyOf(8 * 1024)) }
        when (val result = opener.open(file)) {
            is ZimOpenResult.Failed -> Unit
            is ZimOpenResult.Opened -> result.archive.use { archive ->
                archive.read("index")
                archive.read("Photosynthesis")
                archive.search("chlorophyll", 5)
                archive.suggest("pho", 5)
            }
        }
    }
}
