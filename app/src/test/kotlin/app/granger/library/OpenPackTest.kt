package app.granger.library

import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.core.library.PackStatus
import app.granger.pack
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OpenPackTest {
    private val library = FakeLibrary()
    private val sessions = FakeReadingSessions()

    @Test fun openStartsATabAtTheLastReadPath() = runTest {
        library.packList.value = listOf(pack("wiki", "Wikipedia"))
        library.lastRead["wiki"] = "A/Ada"
        val tab = openPack(library, sessions, "wiki")
        assertThat(tab).isNotNull()
        val opened = sessions.tab(tab!!)!!
        assertThat(opened.packId).isEqualTo("wiki")
        assertThat(opened.path).isEqualTo("A/Ada")
        assertThat(opened.title).isEqualTo("Wikipedia")
    }

    @Test fun anUnavailablePackOpensNothing() = runTest {
        library.packList.value = listOf(pack("gone", status = PackStatus.MISSING), pack("blank"))
        library.lastRead["gone"] = "A/Ada"
        assertThat(openPack(library, sessions, "gone")).isNull()
        // No start page.
        assertThat(openPack(library, sessions, "blank")).isNull()
        // Not in the library at all.
        assertThat(openPack(library, sessions, "elsewhere")).isNull()
        assertThat(sessions.tabs.value).isEmpty()
    }
}
