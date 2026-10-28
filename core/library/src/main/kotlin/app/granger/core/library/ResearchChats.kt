package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.research.Passage
import app.granger.core.research.SourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** One question in a chat. */
data class ChatTurn(
    val id: Long,
    val chatId: Long,
    val question: String,
    val outcome: TurnOutcome,
    val answer: String,
    val medical: Boolean,
    val keywords: List<String>,
    /** The numbered passages given to the model, in order. */
    val shown: List<Passage>,
    /** The ranked passages after [shown], not given to the model. */
    val candidates: List<Passage>,
    val createdAt: Long,
    /** The model's own best guess after a decline; blank until asked (never a source-backed answer). */
    val guess: String = "",
)

/** Where each shown passage lives, as the answer cites it (`[1]` is the first); read-aloud and plans reuse these. */
fun ChatTurn.addresses(): List<PassageAddress> = shown.mapIndexed { i, passage -> PassageAddress.cited(passage, answer, i + 1) }

data class ChatSummary(val id: Long, val title: String, val updatedAt: Long)

/** Kept Research chats: questions, answers and the passages they cite. */
interface ResearchChats {
    /** Newest-updated first. */
    fun recent(limit: Int): Flow<List<ChatSummary>>

    /** The chat's turns, oldest first. */
    fun turns(chatId: Long): Flow<List<ChatTurn>>

    suspend fun chat(chatId: Long): ChatSummary?

    /** One turn with its passages, for reading an answer aloud after the fact (Plan V2); null when it is gone. */
    suspend fun turn(turnId: Long): ChatTurn?

    /** Starts a chat; the caller makes [title] (the question cut to 60 characters at a word boundary). */
    suspend fun create(title: String): Long

    /** Adds a PENDING turn. A [quote] is stored as the first SHOWN passage. */
    suspend fun startTurn(chatId: Long, question: String, quote: Passage?): Long

    /** Settles a turn, replaces its passages and bumps the chat's `updatedAt`. */
    suspend fun finishTurn(
        turnId: Long,
        outcome: TurnOutcome,
        answer: String,
        medical: Boolean,
        keywords: List<String>,
        shown: List<Passage>,
        candidates: List<Passage>,
    )

    /** Keeps the model's best guess on a declined turn, and bumps the chat's `updatedAt`. */
    suspend fun setGuess(turnId: Long, text: String)

    /** Marks every PENDING turn INTERRUPTED (the app died mid-answer); returns how many. */
    suspend fun interruptPending(): Int

    /** Removes the chat with its turns and passages. */
    suspend fun delete(chatId: Long)

    /** Removes every chat with its turns and passages (Plan U3b Decision 7). */
    suspend fun deleteAll()
}

class RoomResearchChats(
    private val db: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : ResearchChats {
    private val dao = db.researchDao()

    override fun recent(limit: Int): Flow<List<ChatSummary>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.summary() } }

    override fun turns(chatId: Long): Flow<List<ChatTurn>> =
        combine(dao.observeTurns(chatId), dao.observePassages(chatId)) { turns, passages ->
            val byTurn = passages.groupBy { it.turnId }
            turns.map { turn -> turn.toChatTurn(byTurn[turn.id].orEmpty()) }
        }

    override suspend fun turn(turnId: Long): ChatTurn? {
        val turn = dao.turn(turnId) ?: return null
        val rows = dao.observePassages(turn.chatId).first().filter { it.turnId == turnId }
        return turn.toChatTurn(rows)
    }

    private fun ResearchTurn.toChatTurn(rows: List<ResearchPassage>): ChatTurn = ChatTurn(
        id = id,
        chatId = chatId,
        question = question,
        outcome = outcome,
        answer = answer,
        medical = medical,
        keywords = if (keywords.isEmpty()) emptyList() else keywords.split(KEYWORD_SEPARATOR),
        shown = rows.filter { it.role == PassageRole.SHOWN }.map { it.toPassage() },
        candidates = rows.filter { it.role == PassageRole.CANDIDATE }.map { it.toPassage() },
        createdAt = createdAt,
        guess = guess,
    )

    override suspend fun chat(chatId: Long): ChatSummary? = dao.chat(chatId)?.summary()

    override suspend fun create(title: String): Long {
        val time = now()
        return dao.insertChat(ResearchChat(0, title, time, time))
    }

    override suspend fun startTurn(chatId: Long, question: String, quote: Passage?): Long =
        db.withTransaction {
            val turnId = dao.insertTurn(
                ResearchTurn(0, chatId, question, TurnOutcome.PENDING, "", false, false, false, "", now()),
            )
            if (quote != null) dao.insertPassages(listOf(quote.toRow(turnId, 1, PassageRole.SHOWN)))
            turnId
        }

    override suspend fun finishTurn(
        turnId: Long,
        outcome: TurnOutcome,
        answer: String,
        medical: Boolean,
        keywords: List<String>,
        shown: List<Passage>,
        candidates: List<Passage>,
    ) {
        db.withTransaction {
            val turn = dao.turn(turnId) ?: return@withTransaction
            dao.updateTurn(
                turn.copy(
                    outcome = outcome,
                    answer = answer,
                    medical = medical,
                    keywords = keywords.joinToString(KEYWORD_SEPARATOR),
                ),
            )
            dao.deletePassages(turnId)
            // Candidates carry on from the shown ordinals so the pair (turn, ordinal) stays unique.
            dao.insertPassages(
                shown.mapIndexed { i, p -> p.toRow(turnId, i + 1, PassageRole.SHOWN) } +
                    candidates.mapIndexed { i, p -> p.toRow(turnId, shown.size + i + 1, PassageRole.CANDIDATE) },
            )
            dao.touchChat(turn.chatId, now())
        }
    }

    override suspend fun setGuess(turnId: Long, text: String) {
        db.withTransaction {
            val turn = dao.turn(turnId) ?: return@withTransaction
            dao.updateTurn(turn.copy(guess = text))
            dao.touchChat(turn.chatId, now())
        }
    }

    override suspend fun interruptPending(): Int = dao.interruptPending()

    override suspend fun delete(chatId: Long) {
        db.withTransaction {
            dao.deletePassagesForChat(chatId)
            dao.deleteTurnsForChat(chatId)
            dao.deleteChat(chatId)
        }
    }

    override suspend fun deleteAll() {
        db.withTransaction {
            dao.deleteAllPassages()
            dao.deleteAllTurns()
            dao.deleteAllChats()
        }
    }

    private fun ResearchChat.summary() = ChatSummary(id, title, updatedAt)

    private fun Passage.toRow(turnId: Long, ordinal: Int, role: PassageRole) = ResearchPassage(
        turnId = turnId, ordinal = ordinal, role = role, kind = kind, sourceId = sourceId,
        sourceTitle = sourceTitle, packTitle = packTitle, location = location, anchor = anchor,
        progression = progression, heading = heading, text = text, author = meta.author,
        date = meta.date, subjects = meta.subjects, collection = meta.collection,
    )

    private fun ResearchPassage.toPassage() = Passage(
        kind = kind, sourceId = sourceId, sourceTitle = sourceTitle, packTitle = packTitle, location = location,
        anchor = anchor, progression = progression, heading = heading, text = text,
        meta = SourceMeta(author, date, subjects, collection),
    )

    private companion object {
        const val KEYWORD_SEPARATOR = "; "
    }
}
