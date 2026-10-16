package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VmRssTest {
    @Test fun parsesVmRssFromProcStatus() {
        val status = "Name:\tapp.granger\nVmHWM:\t 2345678 kB\nVmRSS:\t  123456 kB\nThreads:\t42\n"
        assertThat(parseVmRssKb(status)).isEqualTo(123456)
    }

    @Test fun missingVmRssIsNull() {
        assertThat(parseVmRssKb("Name:\tx\n")).isNull()
    }
}
