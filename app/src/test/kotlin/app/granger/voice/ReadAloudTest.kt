package app.granger.voice

import app.granger.core.speech.ReadAddress
import app.granger.core.speech.TextReadSource
import app.granger.player.FakePlayerHub
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReadAloudTest {
    private val hub = FakePlayerHub()
    private val opened = mutableListOf<Pair<String, String>>()
    private val sources = mapOf(
        "pack:wiki/Photosynthesis" to TextReadSource("pack:wiki/Photosynthesis", "Photosynthesis", "Plants make sugar."),
        "text:answer:7" to TextReadSource("text:answer:7", "Why?", "Because."),
    )
    private val readAloud = ReadAloud(hub) { key, title -> opened += key to title; sources[key] }

    @Test fun anArticleStartsAtItsFirstSentenceWithATitleFromThePathWhenNoneIsGiven() = runTest {
        assertThat(readAloud.start(ReadTarget.Article("wiki", "A/Photosynthesis"))).isFalse()      // not in the fake's sources
        assertThat(readAloud.start(ReadTarget.Article("wiki", "Photosynthesis"))).isTrue()
        assertThat(opened.last()).isEqualTo("pack:wiki/Photosynthesis" to "Photosynthesis")
        assertThat(hub.calls).containsExactly("readAloud:pack:wiki/Photosynthesis@null")
        assertThat(readAloud.start(ReadTarget.Article("wiki", "Photosynthesis", title = "Given"), from = ReadAddress.Article("wiki", "Photosynthesis", "g2", 1))).isTrue()
        assertThat(opened.last().second).isEqualTo("Given")
        assertThat(hub.calls.last()).isEqualTo("readAloud:pack:wiki/Photosynthesis@pack:wiki/Photosynthesis#g2:1")
    }

    @Test fun anAnswerReadsTheStoredTurnAndAMissingTargetIsFalse() = runTest {
        assertThat(readAloud.start(ReadTarget.Answer(7))).isTrue()
        assertThat(hub.calls).containsExactly("readAloud:text:answer:7@null")
        assertThat(readAloud.start(ReadTarget.Answer(8))).isFalse()
        assertThat(readAloud.start(ReadTarget.Book("book:gone"))).isFalse()
        assertThat(hub.calls).hasSize(1)
    }

    @Test fun aSourceThatThrowsIsFalseNotACrash() = runTest {
        val failing = ReadAloud(hub) { _, _ -> throw IllegalStateException("db closed") }
        assertThat(failing.start(ReadTarget.Answer(1))).isFalse()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun listenStartsAtTheSavedPlaceWhenThePageIsAtOrBeforeItElseAtThePage() = runTest {
        val text = TextReadSource("text:t", "T", "One. Two.\n\nThree. Four.")
        val saved = ReadAddress.Text("text:t", 1, 0)                                           // "Three."
        assertThat(listenStart(text, ReadAddress.Text("text:t", 0, 0), saved)).isEqualTo(saved)
        assertThat(listenStart(text, saved, saved)).isEqualTo(saved)
        assertThat(listenStart(text, ReadAddress.Text("text:t", 1, 1), saved)).isEqualTo(ReadAddress.Text("text:t", 1, 1))
        assertThat(listenStart(text, ReadAddress.Text("text:t", 0, 1), null)).isEqualTo(ReadAddress.Text("text:t", 0, 1))
        assertThat(listenStart(text, ReadAddress.Text("text:t", 0, 1), ReadAddress.Text("text:t", 9, 0))).isEqualTo(ReadAddress.Text("text:t", 0, 1))
    }
}
