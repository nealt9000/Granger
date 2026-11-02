package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingTextReadSourceTest {
    @Test fun closedSentencesAreServedWhileTheLastOneWaits() = runTest {
        val s = StreamingTextReadSource("answer:1", "Why is the sky blue?")
        s.append("Light scatters. Blue scatters mo")
        assertThat(s.utterances(null, 10).map { it.text }).containsExactly("Light scatters.")
        s.append("Light scatters. Blue scatters most. ")
        assertThat(s.utterances(null, 10).map { it.text }).containsExactly("Light scatters.", "Blue scatters most.").inOrder()
        s.append("Light scatters. Blue scatters most. So the")
        assertThat(s.utterances(ReadAddress.Text("answer:1", 0, 0), 10).map { it.text }).containsExactly("Light scatters.", "Blue scatters most.").inOrder()
    }

    @Test fun aReaderCaughtUpWithTheTextWaitsForTheNextSentenceInsteadOfEnding() = runTest {
        val s = StreamingTextReadSource("answer:1", "Q")
        s.append("Hold on to your hat.\n\n")                    // the owl's reaction, long before the answer
        val last = s.utterances(null, 10).last().address
        val next = async { s.utterances(last, 10) }
        advanceUntilIdle()
        assertThat(next.isCompleted).isFalse()
        s.append("Hold on to your hat.\n\nReading the likeliest 4…\n\n")
        assertThat(next.await().map { it.text }).containsExactly("Hold on to your hat.", "Reading the likeliest 4…").inOrder()
        // Once complete, the last sentence is served on its own and then nothing.
        s.complete()
        val end = s.utterances(null, 10).last().address
        assertThat(s.utterances(end, 10).map { it.text }).containsExactly("Reading the likeliest 4…")
    }

    @Test fun utterancesWaitForMoreTextAndCompleteEndsTheSource() = runTest {
        val s = StreamingTextReadSource("answer:1", "Q")
        s.append("First one.")
        val waiting = async { s.utterances(ReadAddress.Text("answer:1", 0, 1), 10) }
        advanceUntilIdle()
        assertThat(waiting.isCompleted).isFalse()                 // "First one." may still grow; nothing after it yet
        s.append("First one. Second one.")
        s.complete()
        assertThat(waiting.await().map { it.text }).containsExactly("Second one.")
        assertThat(s.utterances(ReadAddress.Text("answer:1", 0, 2), 10)).isEmpty()
        assertThat(s.isComplete).isTrue()
    }

    @Test fun paragraphsAndThePrefaceKeepTheirAddresses() = runTest {
        val s = StreamingTextReadSource("answer:2", "Q", preface = "Not medical advice.")
        s.append("Rest. Drink water.\n\nSee a doctor if it lasts.")
        s.complete()
        val all = s.utterances(null, 10)
        assertThat(all.map { it.text }).containsExactly("Not medical advice.", "Rest.", "Drink water.", "See a doctor if it lasts.").inOrder()
        assertThat(all.map { it.address }).containsExactly(
            ReadAddress.Text("answer:2", 0, 0), ReadAddress.Text("answer:2", 1, 0), ReadAddress.Text("answer:2", 1, 1), ReadAddress.Text("answer:2", 2, 0),
        ).inOrder()
        assertThat(all.map { it.paragraph }).containsExactly(0, 1, 1, 2).inOrder()
    }
}
