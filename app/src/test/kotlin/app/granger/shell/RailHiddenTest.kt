package app.granger.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RailHiddenTest {
    private val shared = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("rail-hidden-test", Context.MODE_PRIVATE)

    @Test fun hiddenItemsAreKeptAcrossLaunches() {
        SharedRailHidden(shared).hide("chat:3", 40L)
        SharedRailHidden(shared).hide("play:songs", 50L)
        assertThat(SharedRailHidden(shared).hidden.value).containsExactly("chat:3", 40L, "play:songs", 50L)
    }

    @Test fun anItemUsedSinceItWasHiddenShowsAgain() {
        val hidden = mapOf("chat:3" to 40L)
        assertThat(hidden.hides(RailEntry.Chat(3, "Tides", 40L))).isTrue()
        assertThat(hidden.hides(RailEntry.Chat(3, "Tides", 41L))).isFalse()
        assertThat(hidden.hides(RailEntry.Chat(4, "Other", 1L))).isFalse()
    }

    @Test fun onlyTheNewestHundredAreKept() {
        val store = SharedRailHidden(shared)
        for (i in 1..105) store.hide("chat:$i", i.toLong())
        val kept = SharedRailHidden(shared).hidden.value
        assertThat(kept).hasSize(100)
        assertThat(kept.keys).doesNotContain("chat:5")
        assertThat(kept.keys).contains("chat:6")
    }
}
