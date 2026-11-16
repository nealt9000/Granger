package app.granger.core.zim

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Plan KB2 Task 0.2: [ZimArchive.list]'s default. An archive that can't walk its directory says so rather than
 * listing nothing as if the pack were empty; libkiwix's walk is tested on the device (KiwixZimArchiveTest).
 */
class ZimArchiveListTest {
    @Test fun anArchiveWithoutAWalkSaysItCantList() {
        val archive = FakeZimArchive(entries = mapOf("files/a.pdf" to ("application/pdf" to "%PDF")))
        val seen = mutableListOf<ZimEntry>()
        assertThat(archive.list(setOf("application/pdf")) { seen += it; true }).isFalse()
        assertThat(seen).isEmpty()
    }
}
