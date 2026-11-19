package app.granger.online

import app.granger.core.library.KeptArticle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan O5 Task 4: which kept articles have a newer version, checked once a day. */
class KeptUpdatesTest {
    private fun kept(title: String, rev: Long?) = KeptArticle("en", title, null, "en/x", 1, true, 0, rev)

    @Test fun onlyTheOnesBehindAreListed() {
        val live = mapOf("Moon" to 20L, "Knowth" to 10L)
        val behind = KeptUpdates.behindOf(listOf(kept("Moon", 15), kept("Knowth", 10), kept("Dowth", null))) { _, _ -> live }
        assertThat(behind.map { it.title }).containsExactly("Moon")
    }

    @Test fun onceADayWhileOnline() {
        var asked = 0
        var last = 0L
        var now = KeptUpdates.DAY_MS * 3
        val u = KeptUpdates({ _, _ -> asked++; mapOf("Moon" to 20L) }, { last }, { last = it }, { now })
        u.check(listOf(kept("Moon", 15)), online = false)
        assertThat(asked).isEqualTo(0)
        u.check(listOf(kept("Moon", 15)), online = true)
        assertThat(asked).isEqualTo(1)
        assertThat(u.updates.value.map { it.title }).containsExactly("Moon")
        now += 1000
        u.check(listOf(kept("Moon", 15)), online = true)
        assertThat(asked).isEqualTo(1)
        u.done("en", "Moon")
        assertThat(u.updates.value).isEmpty()
    }
}
