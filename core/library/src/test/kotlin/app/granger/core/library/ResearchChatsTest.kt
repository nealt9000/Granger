package app.granger.core.library

import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResearchChatsTest {
    private val db = inMemoryDatabase()
    private var clock = 1_000L
    private val chats = RoomResearchChats(db) { clock++ }

    @After fun close() = db.close()

    private fun passage(kind: SourceKind, n: Int, anchor: String? = null, progression: Double = 0.0) = Passage(
        kind = kind,
        sourceId = "src$n",
        sourceTitle = "Title $n",
        packTitle = "Pack $n",
        location = "loc$n",
        anchor = anchor,
        progression = progression,
        heading = "Heading $n",
        text = "Text $n",
        meta = SourceMeta(author = "Author $n", date = "2026", subjects = "a; b", collection = "Coll $n"),
    )

    @Test fun turnRoundTripsWithPassagesInOrder() = runTest {
        val chatId = chats.create("Why is the sky blue?")
        val turnId = chats.startTurn(chatId, "Why is the sky blue?", quote = null)
        val shown = listOf(
            passage(SourceKind.ARTICLE, 1, anchor = "Rayleigh"),
            passage(SourceKind.BOOK, 2, progression = 0.42),
            passage(SourceKind.CARD, 3),
            passage(SourceKind.SELECTION, 4),
        )
        val candidates = listOf(passage(SourceKind.ARTICLE, 5), passage(SourceKind.BOOK, 6, progression = 0.9))
        chats.finishTurn(turnId, TurnOutcome.ANSWERED, "Scattering [1].", true, listOf("sky", "blue light"), shown, candidates)

        val turn = chats.turns(chatId).first().single()
        assertThat(turn).isEqualTo(
            ChatTurn(
                id = turnId, chatId = chatId, question = "Why is the sky blue?", outcome = TurnOutcome.ANSWERED,
                answer = "Scattering [1].", medical = true,
                keywords = listOf("sky", "blue light"), shown = shown, candidates = candidates, createdAt = turn.createdAt,
            ),
        )
    }

    @Test fun mediaPassagesRoundTrip() = runTest {
        val chatId = chats.create("Who composed The Planets?")
        val turnId = chats.startTurn(chatId, "Who composed The Planets?", quote = null)
        val media = passage(SourceKind.MEDIA, 1).copy(sourceId = "album:gustav holst|the planets", location = "", packTitle = "Your music")
        chats.finishTurn(turnId, TurnOutcome.ANSWERED, "Holst [1].", false, listOf("planets"), listOf(media), listOf(passage(SourceKind.MEDIA, 2)))
        val turn = chats.turns(chatId).first().single()
        assertThat(turn.shown).containsExactly(media)
        assertThat(turn.candidates.single().kind).isEqualTo(SourceKind.MEDIA)
    }

    @Test fun emptyKeywordsRoundTripAsEmpty() = runTest {
        val chatId = chats.create("q")
        val turnId = chats.startTurn(chatId, "q", null)
        chats.finishTurn(turnId, TurnOutcome.DECLINED, "", false, emptyList(), emptyList(), emptyList())
        assertThat(chats.turns(chatId).first().single().keywords).isEmpty()
    }

    @Test fun quoteIsStoredAsPassageOne() = runTest {
        val chatId = chats.create("q")
        val quote = passage(SourceKind.SELECTION, 9)
        chats.startTurn(chatId, "q", quote)
        val turn = chats.turns(chatId).first().single()
        assertThat(turn.outcome).isEqualTo(TurnOutcome.PENDING)
        assertThat(turn.shown).containsExactly(quote)
    }

    @Test fun recentIsNewestUpdatedFirst() = runTest {
        val a = chats.create("A")
        val b = chats.create("B")
        assertThat(chats.recent(10).first().map { it.id }).containsExactly(b, a).inOrder()
        val turn = chats.startTurn(a, "q", null)
        chats.finishTurn(turn, TurnOutcome.ANSWERED, "x", false, emptyList(), emptyList(), emptyList())
        assertThat(chats.recent(10).first().map { it.id }).containsExactly(a, b).inOrder()
        assertThat(chats.recent(1).first()).hasSize(1)
    }

    @Test fun finishingATurnBumpsTheChat() = runTest {
        val id = chats.create("A")
        val before = chats.chat(id)!!.updatedAt
        val turn = chats.startTurn(id, "q", null)
        chats.finishTurn(turn, TurnOutcome.ANSWERED, "x", false, emptyList(), emptyList(), emptyList())
        assertThat(chats.chat(id)!!.updatedAt).isGreaterThan(before)
        assertThat(chats.chat(id)!!.title).isEqualTo("A")
        assertThat(chats.chat(999)).isNull()
    }

    @Test fun finishReplacesThePassages() = runTest {
        val id = chats.create("A")
        val turn = chats.startTurn(id, "q", passage(SourceKind.SELECTION, 1))
        val shown = listOf(passage(SourceKind.CARD, 2))
        chats.finishTurn(turn, TurnOutcome.ANSWERED, "x", false, emptyList(), shown, emptyList())
        assertThat(chats.turns(id).first().single().shown).isEqualTo(shown)
    }

    @Test fun interruptPendingMarksOnlyPending() = runTest {
        val id = chats.create("A")
        val done = chats.startTurn(id, "q1", null)
        chats.finishTurn(done, TurnOutcome.ANSWERED, "x", false, emptyList(), emptyList(), emptyList())
        chats.startTurn(id, "q2", null)
        assertThat(chats.interruptPending()).isEqualTo(1)
        assertThat(chats.turns(id).first().map { it.outcome })
            .containsExactly(TurnOutcome.ANSWERED, TurnOutcome.INTERRUPTED).inOrder()
        assertThat(chats.interruptPending()).isEqualTo(0)
    }

    @Test fun deleteRemovesTurnsAndPassages() = runTest {
        val id = chats.create("A")
        val other = chats.create("B")
        val t1 = chats.startTurn(id, "q", passage(SourceKind.SELECTION, 1))
        chats.finishTurn(t1, TurnOutcome.ANSWERED, "x", false, emptyList(), listOf(passage(SourceKind.CARD, 2)), emptyList())
        chats.startTurn(other, "q", passage(SourceKind.SELECTION, 3))
        chats.delete(id)
        assertThat(chats.chat(id)).isNull()
        assertThat(chats.turns(id).first()).isEmpty()
        assertThat(chats.turns(other).first().single().shown).hasSize(1)
        db.query("SELECT COUNT(*) FROM research_passages", null).use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun deleteAllRemovesEveryChatWithItsTurnsAndPassages() = runTest {
        for (title in listOf("A", "B")) {
            val id = chats.create(title)
            val turn = chats.startTurn(id, "q", passage(SourceKind.SELECTION, 1))
            chats.finishTurn(turn, TurnOutcome.ANSWERED, "x", false, emptyList(), listOf(passage(SourceKind.CARD, 2)), emptyList())
        }
        chats.deleteAll()
        assertThat(chats.recent(10).first()).isEmpty()
        for (table in listOf("research_chats", "research_turns", "research_passages")) {
            db.query("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                assertThat(it.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test fun setGuessIsReadBack() = runTest {
        val chatId = chats.create("q")
        val turnId = chats.startTurn(chatId, "q", quote = null)
        chats.finishTurn(turnId, TurnOutcome.DECLINED, "", false, emptyList(), emptyList(), emptyList())
        val before = chats.turns(chatId).first().single()
        assertThat(before.guess).isEmpty()

        chats.setGuess(turnId, "Probably chlorophyll.")

        val turn = chats.turns(chatId).first().single()
        assertThat(turn.guess).isEqualTo("Probably chlorophyll.")
        assertThat(turn.outcome).isEqualTo(TurnOutcome.DECLINED)
    }
}
