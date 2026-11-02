package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTagReadGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir by lazy { File(tmp.root, "guard") }

    /** A run that dies while reading [uri]: the mark is left behind, as a process death leaves it. */
    private fun dieReading(uri: String) {
        val guard = FileTagReadGuard(dir)
        guard.skipped()
        guard.begin(uri)
    }

    @Test fun oneDeathOnlyMakesTheFileSuspect() {
        dieReading("content://media/2")
        assertThat(FileTagReadGuard(dir).skipped()).isEmpty()
    }

    @Test fun aSecondDeathOnTheSameFileSkipsItForGood() {
        dieReading("content://media/2")
        dieReading("content://media/2")
        assertThat(FileTagReadGuard(dir).skipped()).containsExactly("content://media/2")
        assertThat(FileTagReadGuard(dir).skipped()).containsExactly("content://media/2")
    }

    @Test fun aSuspectReadCleanlyStartsAgainFromNoStrikes() {
        dieReading("content://media/2")
        val guard = FileTagReadGuard(dir)
        assertThat(guard.skipped()).isEmpty()
        guard.begin("content://media/2")
        guard.end()
        dieReading("content://media/2")   // killed for memory a week later: suspect again, not skipped
        assertThat(FileTagReadGuard(dir).skipped()).isEmpty()
    }

    @Test fun deathsOnDifferentFilesDontAddUp() {
        dieReading("content://media/1")
        dieReading("content://media/2")
        assertThat(FileTagReadGuard(dir).skipped()).isEmpty()
    }

    @Test fun anOldOneStrikeSkipGetsOneMoreRead() {
        dir.mkdirs()
        File(dir, "tags-skipped").writeText("content://media/9\n")
        assertThat(FileTagReadGuard(dir).skipped()).isEmpty()
        assertThat(File(dir, "tags-skipped").exists()).isFalse()
        dieReading("content://media/9")   // it really does crash: now it's skipped
        assertThat(FileTagReadGuard(dir).skipped()).containsExactly("content://media/9")
    }
}
