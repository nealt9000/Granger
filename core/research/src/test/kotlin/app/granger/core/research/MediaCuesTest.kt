package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaCuesTest {
    @Test fun mediaWordsAreCues() {
        assertThat(MediaCues.asked("What's on the album The Wall?")).isTrue()
        assertThat(MediaCues.asked("Who narrated Wicked?")).isTrue()
        assertThat(MediaCues.asked("Which version of The Jungle Book is read by a woman?")).isTrue()
        assertThat(MediaCues.asked("Who wrote the SYMPHONY?")).isTrue()
        assertThat(MediaCues.asked("Which AUDIOBOOKS do I have?")).isTrue()
    }

    @Test fun ordinaryQuestionsAreNot() {
        assertThat(MediaCues.asked("How far is Mars from the Sun?")).isFalse()
        assertThat(MediaCues.asked("Who invented the recorder?")).isFalse()
        assertThat(MediaCues.asked("Who was the first to read it by candlelight?")).isFalse()
        assertThat(MediaCues.asked("What is a bandwidth?")).isFalse()
        assertThat(MediaCues.asked("")).isFalse()
    }
}
