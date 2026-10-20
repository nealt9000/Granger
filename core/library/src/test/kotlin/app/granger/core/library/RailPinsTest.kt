package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RailPinsTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val pins = RoomRailPins(db) { clock++ }

    @After fun close() = db.close()

    private suspend fun keys() = pins.pins.first().map { it.key }

    @Test fun pinThenListNewestFirst() = runTest {
        assertThat(pins.pin("tab:1")).isEqualTo(PinResult.PINNED)
        assertThat(pins.pin("search:holst")).isEqualTo(PinResult.PINNED)
        assertThat(pins.pin("chat:3")).isEqualTo(PinResult.PINNED)
        assertThat(keys()).containsExactly("chat:3", "search:holst", "tab:1").inOrder()
        // Pinning again changes nothing, not even the order.
        assertThat(pins.pin("tab:1")).isEqualTo(PinResult.ALREADY_PINNED)
        assertThat(keys()).containsExactly("chat:3", "search:holst", "tab:1").inOrder()
    }

    @Test fun seventhPinIsRefused() = runTest {
        repeat(MAX_RAIL_PINS) { assertThat(pins.pin("tab:$it")).isEqualTo(PinResult.PINNED) }
        assertThat(pins.pin("tab:99")).isEqualTo(PinResult.FULL)
        assertThat(keys()).hasSize(MAX_RAIL_PINS)
        assertThat(keys()).doesNotContain("tab:99")
        // A pin already there still answers as pinned when the list is full.
        assertThat(pins.pin("tab:0")).isEqualTo(PinResult.ALREADY_PINNED)
    }

    @Test fun unpinRemoves() = runTest {
        pins.pin("tab:1")
        pins.pin("tab:2")
        pins.unpin("tab:1")
        pins.unpin("tab:404")
        assertThat(keys()).containsExactly("tab:2")
    }

    @Test fun dropMissingDeletesOnlyThoseKeys() = runTest {
        pins.pin("tab:1")
        pins.pin("chat:3")
        pins.pin("play:album:x")
        pins.dropMissing(listOf("chat:3", "play:album:x", "search:never-pinned"))
        pins.dropMissing(emptyList())
        assertThat(keys()).containsExactly("tab:1")
    }
}
