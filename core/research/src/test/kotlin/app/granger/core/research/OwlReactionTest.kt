package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class OwlReactionTest {
    private val centroids = TopicCentroids(
        mapOf(
            ReactionTopic.HISTORY to floatArrayOf(1f, 0f, 0f),
            ReactionTopic.SCIENCE to floatArrayOf(0f, 1f, 0f),
            ReactionTopic.MEDICAL to floatArrayOf(0f, 0f, 1f),
        ),
        floor = 0.5f, margin = 0.1f,
    )

    @Test
    fun aClearMatchTakesItsTopic() {
        assertThat(centroids.classify(floatArrayOf(0.9f, 0.1f, 0f))).isEqualTo(ReactionTopic.HISTORY)
    }

    @Test
    fun aCloseCallOrAWeakMatchIsTheDefault() {
        // Best and runner-up within the margin.
        assertThat(centroids.classify(floatArrayOf(0.7f, 0.65f, 0f))).isEqualTo(ReactionTopic.DEFAULT)
        // Nearest is history, but below the floor once normalized against a big off-axis part.
        assertThat(centroids.classify(floatArrayOf(0.3f, 0f, -0.9f))).isEqualTo(ReactionTopic.DEFAULT)
        assertThat(centroids.classify(floatArrayOf(0f, 0f, 0f))).isEqualTo(ReactionTopic.DEFAULT)
    }

    @Test
    fun nearestMedicalIsMedicalEvenInACloseCall() {
        assertThat(centroids.classify(floatArrayOf(0.6f, 0f, 0.62f))).isEqualTo(ReactionTopic.MEDICAL)
    }

    @Test
    fun aWeakMedicalNearestIsTheDefault() {
        val guarded = TopicCentroids(
            mapOf(ReactionTopic.HISTORY to floatArrayOf(1f, 0f), ReactionTopic.MEDICAL to floatArrayOf(0f, 1f)), 0f, 0.1f, medicalFloor = 0.8f,
        )
        assertThat(guarded.classify(floatArrayOf(0.1f, 1f))).isEqualTo(ReactionTopic.MEDICAL)   // cosine 0.995
        assertThat(guarded.classify(floatArrayOf(0.9f, 1f))).isEqualTo(ReactionTopic.DEFAULT)   // cosine 0.74
    }

    @Test
    fun theLexiconPlacesHealthAndFollowUpsWithoutTheEmbedder() {
        assertThat(OwlReaction.lexical("What are the symptoms of measles?", null)).isEqualTo(ReactionTopic.MEDICAL)
        assertThat(OwlReaction.lexical("Can I give my toddler ibuprofen?", null)).isEqualTo(ReactionTopic.MEDICAL)
        assertThat(OwlReaction.lexical("When was he born?", "Who wrote Hamlet?")).isEqualTo(ReactionTopic.FOLLOW_UP)
        // A pronoun in a first question isn't a follow-up; a question that stands alone isn't either.
        assertThat(OwlReaction.lexical("When was he born?", null)).isNull()
        assertThat(OwlReaction.lexical("Who wrote Moby-Dick?", "Who wrote Hamlet?")).isNull()
    }

    @Test
    fun parsesTheAssetFormat() {
        val parsed = TopicCentroids.parse(
            """
            # comment
            embedder abc123
            floor 0.5
            margin 0.1
            HISTORY 1 0 0
            SCIENCE 0 1 0
            MEDICAL 0 0 1
            """.trimIndent(),
        )
        assertThat(parsed.embedderSha).isEqualTo("abc123")
        assertThat(parsed.centroids.classify(floatArrayOf(0f, 1f, 0f))).isEqualTo(ReactionTopic.SCIENCE)
    }

    @Test
    fun rejectsABrokenAsset() {
        assertThrows(IllegalArgumentException::class.java) { TopicCentroids.parse("embedder a\nfloor 0.5\nmargin 0.1\nGOSSIP 1 0") }
        assertThrows(IllegalArgumentException::class.java) { TopicCentroids.parse("HISTORY 1 0\nSCIENCE 0 1") }
        assertThrows(IllegalArgumentException::class.java) { TopicCentroids.parse("embedder a\nfloor 0.5\nmargin 0.1\nHISTORY 1 0\nSCIENCE 0 1 0") }
    }

    @Test
    fun everyTopicHasLinesAndMedicalOnesArePlain() {
        for (topic in ReactionTopic.entries) assertThat(OwlLines.LINES[topic]).isNotEmpty()
        // Plain: no exclamations, no jokes; they say what is searched.
        for (line in OwlLines.LINES.getValue(ReactionTopic.MEDICAL)) {
            assertThat(line).doesNotContain("!")
            assertThat(line.lowercase()).containsMatch("medical|health|references")
        }
        for (line in OwlLines.LINES.values.flatten()) assertThat(line.length).isAtMost(64)
    }

    @Test
    fun aChatHearsEveryLineBeforeOneRepeats() {
        val said = mutableSetOf<String>()
        val lines = OwlLines.LINES.getValue(ReactionTopic.SCRIPTURE)
        repeat(lines.size) { said += OwlLines.pick(ReactionTopic.SCRIPTURE, said, Random(it)) }
        assertThat(said).containsExactlyElementsIn(lines)
        assertThat(OwlLines.pick(ReactionTopic.SCRIPTURE, said, Random(1))).isIn(lines)
    }

    @Test
    fun aDeclinedTurnKeepsItsLineAndLabel() {
        for (id in listOf(0L, 1L, 7L, 8L, 12345L, -3L)) {
            assertThat(OwlLines.decline(id)).isEqualTo(OwlLines.decline(id))
            assertThat(OwlLines.decline(id)).isIn(OwlLines.DECLINES)
            assertThat(OwlLines.guessLabel(id)).isIn(OwlLines.GUESS_LABELS)
        }
        // Neighbouring turns in a chat get different lines.
        assertThat(OwlLines.decline(41L)).isNotEqualTo(OwlLines.decline(42L))
        // No markdown on screen; every guess label says it's unchecked.
        assertThat(OwlLines.DECLINES.none { '*' in it }).isTrue()
        for (label in OwlLines.GUESS_LABELS) assertThat(label.lowercase()).containsMatch("book|guess|memory")
    }

    @Test
    fun readingCountsItsPassagesInGoodEnglish() {
        for (seed in 0..5) {
            val one = OwlLines.reading(1, Random(seed))
            assertThat(one).isIn(OwlLines.READING.map { it.second })
            assertThat(one).doesNotContain("passages")
            val four = OwlLines.reading(4, Random(seed))
            assertThat(four).contains("4")
            assertThat(four).doesNotContain("{n}")
        }
    }

    @Test
    fun wakingLinesAreAllDifferentAndAboutTheOwl() {
        assertThat(OwlLines.WAKING.toSet()).hasSize(OwlLines.WAKING.size)
        assertThat(OwlLines.WAKING.size).isAtLeast(8)
        for (line in OwlLines.WAKING) {
            assertThat(line).endsWith("…")
            assertThat(line.length).isAtMost(40)
        }
    }
}
