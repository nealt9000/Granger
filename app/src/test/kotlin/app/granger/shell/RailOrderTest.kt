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
class RailOrderTest {
    private val shared = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("rail-order-test", Context.MODE_PRIVATE)

    private fun search(q: String, at: Long) = RailEntry.Search(q, at)

    @Test fun theFourAreKeptAcrossLaunches() {
        assertThat(SharedRailOrder(shared).four.value).isNull()
        SharedRailOrder(shared).save(RailFour(listOf("tab:3", "search:a|b", "chat:1"), seenAt = 42))
        assertThat(SharedRailOrder(shared).four.value).isEqualTo(RailFour(listOf("tab:3", "search:a|b", "chat:1"), seenAt = 42))
    }

    @Test fun severalArrivingAtOnceEnterNewestOnTop() {
        val four = RailFour(listOf("search:a"), seenAt = 1)
        val candidates = (6 downTo 1).map { search("q$it", it.toLong()) } + search("a", 1)
        assertThat(four.next(candidates)).isEqualTo(RailFour(listOf("search:q6", "search:q5", "search:q4", "search:q3"), seenAt = 6))
    }

    @Test fun nothingNewLeavesTheFourAsTheyAre() {
        val four = RailFour(listOf("search:b", "search:a"), seenAt = 5)
        assertThat(four.next(listOf(search("b", 2), search("a", 1)))).isEqualTo(four)
    }
}
