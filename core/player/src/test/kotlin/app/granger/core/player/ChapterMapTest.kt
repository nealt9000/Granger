package app.granger.core.player

import app.granger.core.library.AudiobookChapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterMapTest {
    // Two files: "a" holds chapters 0–2 (embedded), "b" is one chapter.
    private val map = ChapterMap(listOf(
        AudiobookChapter("book", 0, "One", "a", 0L, 1_000L),
        AudiobookChapter("book", 1, "Two", "a", 1_000L, 1_000L),
        AudiobookChapter("book", 2, "Three", "a", 2_000L, 1_000L),
        AudiobookChapter("book", 3, "Four", "b", 0L, 5_000L),
    ))

    @Test fun filesFollowChapterOrder() {
        assertThat(map.files).containsExactly("a", "b").inOrder()
    }

    @Test fun chapterToPlayerPosition() {
        assertThat(map.start(1)).isEqualTo(ChapterMap.At(0, 1_000L))
        assertThat(map.start(3)).isEqualTo(ChapterMap.At(1, 0L))
    }

    @Test fun playerPositionToChapter() {
        assertThat(map.chapterAt(0, 0L).ordinal).isEqualTo(0)
        assertThat(map.chapterAt(0, 1_999L).ordinal).isEqualTo(1)
        assertThat(map.chapterAt(0, 2_000L).ordinal).isEqualTo(2)
        assertThat(map.chapterAt(1, 4_000L).ordinal).isEqualTo(3)
        assertThat(map.chapterAt(5, 0L).ordinal).isEqualTo(3)       // out of range: clamps to the last
    }

    @Test fun positionWithinTheChapter() {
        assertThat(map.inChapter(0, 1_250L)).isEqualTo(250L)
        assertThat(map.inChapter(1, 4_000L)).isEqualTo(4_000L)
    }
}
