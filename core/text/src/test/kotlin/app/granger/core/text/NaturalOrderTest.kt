package app.granger.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaturalOrderTest {
    @Test fun numbersSortAsNumbers() {
        val names = listOf("p10.jpg", "P2.jpg", "p1.jpg", "cover.jpg", "p02a.jpg")
        assertThat(names.sortedWith(NaturalOrder)).containsExactly("cover.jpg", "p1.jpg", "P2.jpg", "p02a.jpg", "p10.jpg").inOrder()
    }

    @Test fun issuesAndDatesSortInOrder() {
        val names = listOf("Run/Issue 10 (of 12).cbr", "Run\\Issue 9 (of 12).cbr", "Mag 1889-02.pdf", "Mag 1889-10.pdf", "Mag 1889-1.pdf")
        assertThat(names.sortedWith(NaturalOrder)).containsExactly(
            "Mag 1889-1.pdf", "Mag 1889-02.pdf", "Mag 1889-10.pdf", "Run\\Issue 9 (of 12).cbr", "Run/Issue 10 (of 12).cbr",
        ).inOrder()
    }
}
