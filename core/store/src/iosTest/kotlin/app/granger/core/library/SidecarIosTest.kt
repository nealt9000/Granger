package app.granger.core.library

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real release sidecars read on iOS (Plan I3 Task 2). They live outside the repo, so the test runs only when
 * `-PgqidTest=<file.gqid> -PgtimeTest=<file.gtime>` is given (build.gradle.kts passes them to the Simulator).
 */
class SidecarIosTest {
    @OptIn(ExperimentalForeignApi::class)
    private fun env(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    @Test fun theTopQidIndexReads() {
        val path = env("GRANGER_TEST_GQID") ?: return
        val index = assertNotNull(QidIndex.open(path, "wikipedia_en_top"), "opens")
        val moon = assertNotNull(index.qidOf("Moon"), "Moon has a QID")
        assertEquals(405L, moon)
        assertTrue(index.subjectsOf(moon).isNotEmpty(), "Moon has subjects")
        assertNotNull(index.randomPath())
        index.close()
    }

    @Test fun theTopTimeIndexReads() {
        val path = env("GRANGER_TEST_GTIME") ?: return
        val index = assertNotNull(TimeIndex.open(path, "wikipedia_en_top"), "opens")
        val hastings = assertNotNull(index.find("Battle_of_Hastings"), "Hastings is dated")
        assertEquals(1066L, hastings.y0)
        assertTrue(index.top(1900, 1950, Lane.entries.toSet(), 10).size == 10, "a decade's top ten")
        index.close()
    }
}
