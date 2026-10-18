package app.granger.research

import app.granger.FakeReadingSessions
import app.granger.core.library.ReadingSessions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CitationTabTest {
    private val sessions = FakeReadingSessions()

    @Test fun findsTheTab() = runTest {
        val id = sessions.openTab("pack1", "A/Art1", "Article 1")
        assertThat(citationTab(sessions, id)?.id).isEqualTo(id)
    }

    @Test fun aMissingTabIsNull() = runTest {
        assertThat(citationTab(sessions, 99)).isNull()
    }

    @Test fun aStorageErrorOpensNothingRatherThanCrashing() = runTest {
        val broken = object : ReadingSessions by sessions {
            override suspend fun tab(id: Long) = throw IllegalStateException("disk I/O error")
        }
        assertThat(citationTab(broken, 1)).isNull()
    }
}
