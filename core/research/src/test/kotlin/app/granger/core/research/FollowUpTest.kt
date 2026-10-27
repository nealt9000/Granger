package app.granger.core.research

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class FollowUpTest {
    @Test fun theEvalsFollowUpsReferBack() {
        listOf(
            "What other books did she write?", "Which book of the bible are they in?", "How did he die?",
            "How many Nobel Prizes did she win?", "Who was the first to climb it?", "How long did it take him to get home?",
            "Is there a book of his sayings?", "who built it", "Where can you see it today?", "What was his favorite food?",
            "And at altitude?", "What about Mars?",
        ).forEach { assertWithMessage(it).that(FollowUp.refersBack(it)).isTrue() }
    }

    @Test fun questionsThatStandAloneDont() {
        // Neal's chat (Plan R5 Task 7): each was asked after an unrelated question.
        listOf(
            "what year did the Russians land on Venus", "who shot jfk?", "how many years ago did ww2 end?",
            "what's the movie about a pig and a spider?", "what's the story about a red headed orphaned girl?",
            "what's the movie about a lion cub who's father dies?", "how do I keep snakes out of a chicken coup?",
            "what temperature do I need to cook chicken to?", "Android or iPhone?", "Is there a cure for the common cold?",
        ).forEach { assertWithMessage(it).that(FollowUp.refersBack(it)).isFalse() }
    }
}
