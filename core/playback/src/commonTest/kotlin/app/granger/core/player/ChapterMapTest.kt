package app.granger.core.player

import app.granger.core.library.AudiobookChapter
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterMapTest {
    // Two files: "a" holds chapters 0–2 (embedded), "b" is one chapter.
    private val map = ChapterMap(listOf(
        AudiobookChapter("book", 0, "One", "a", 0L, 1_000L),
        AudiobookChapter("book", 1, "Two", "a", 1_000L, 1_000L),
        AudiobookChapter("book", 2, "Three", "a", 2_000L, 1_000L),
        AudiobookChapter("book", 3, "Four", "b", 0L, 5_000L),
    ))

    @Test fun filesFollowChapterOrder() {
        assertEquals(listOf("a", "b"), (map.files).toList())
    }

    @Test fun chapterToPlayerPosition() {
        assertEquals(ChapterMap.At(0, 1_000L), map.start(1))
        assertEquals(ChapterMap.At(1, 0L), map.start(3))
    }

    @Test fun playerPositionToChapter() {
        assertEquals(0, map.chapterAt(0, 0L).ordinal)
        assertEquals(1, map.chapterAt(0, 1_999L).ordinal)
        assertEquals(2, map.chapterAt(0, 2_000L).ordinal)
        assertEquals(3, map.chapterAt(1, 4_000L).ordinal)
        assertEquals(3, map.chapterAt(5, 0L).ordinal)       // out of range: clamps to the last
    }

    @Test fun positionWithinTheChapter() {
        assertEquals(250L, map.inChapter(0, 1_250L))
        assertEquals(4_000L, map.inChapter(1, 4_000L))
    }
}
