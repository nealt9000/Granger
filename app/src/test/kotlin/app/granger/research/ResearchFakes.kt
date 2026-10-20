package app.granger.research

import app.granger.core.library.ChatSummary
import app.granger.core.library.ChatTurn
import app.granger.core.library.ResearchChats
import app.granger.core.library.TurnOutcome
import app.granger.core.research.ModelUnavailableException
import app.granger.core.research.Passage
import app.granger.core.research.PassageEmbedder
import app.granger.core.research.PassageFinder
import app.granger.core.research.ResearchModels
import app.granger.core.research.SourceKind
import app.granger.core.research.SourceMeta
import app.granger.core.research.TextGenerator
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** An article passage that matches the keywords "photosynthesis, light". */
fun article(n: Int) = Passage(
    SourceKind.ARTICLE, "pack$n", "Article $n", packTitle = "Pack $n", location = "A/Art$n", anchor = "sec$n",
    text = "Photosynthesis turns light into sugar in plants, part $n.",
)

/** A media catalogue card for the album "album:[albumKey]" (as `MediaCards` writes it), its title "Album [n]". */
fun media(n: Int, albumKey: String = "key$n") = Passage(
    SourceKind.MEDIA, "album:$albumKey", "Album $n", packTitle = "Your music", location = "",
    text = "Album: Album $n\nArtist: Artist $n\nIn your library as: Music album",
    meta = SourceMeta(author = "Artist $n"),
)

/** A media catalogue card for the audiobook [id]. */
fun mediaBook(id: String, title: String = "Book", author: String = "Author") = Passage(
    SourceKind.MEDIA, id, title, packTitle = "Your audiobooks", location = "",
    text = "Audiobook: $title\nAuthor: $author\nIn your library as: Audiobook",
    meta = SourceMeta(author = author),
)

/** In-memory [ResearchChats]. [failure], when set, is thrown by every call that reads or writes one chat. */
class FakeChats : ResearchChats {
    var failure: Exception? = null
    /** When set, the `turns` and `recent` flows throw it. */
    var flowFailure: Exception? = null
    val chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val turnList = MutableStateFlow<List<ChatTurn>>(emptyList())
    private var nextChat = 1L
    private var nextTurn = 1L
    private var clock = 1_000L

    fun turnsOf(chatId: Long) = turnList.value.filter { it.chatId == chatId }

    override fun recent(limit: Int): Flow<List<ChatSummary>> =
        chats.map { list -> flowFailure?.let { throw it }; list.sortedByDescending { it.updatedAt }.take(limit) }

    override fun turns(chatId: Long): Flow<List<ChatTurn>> = turnList.map { list -> flowFailure?.let { throw it }; list.filter { it.chatId == chatId } }

    override suspend fun chat(chatId: Long): ChatSummary? {
        failure?.let { throw it }
        return chats.value.firstOrNull { it.id == chatId }
    }

    override suspend fun create(title: String): Long {
        failure?.let { throw it }
        val id = nextChat++
        chats.update { it + ChatSummary(id, title, ++clock) }
        return id
    }

    override suspend fun startTurn(chatId: Long, question: String, askedDeeper: Boolean, quote: Passage?): Long {
        failure?.let { throw it }
        val id = nextTurn++
        turnList.update {
            it + ChatTurn(
                id, chatId, question, TurnOutcome.PENDING, "", false, false, askedDeeper, emptyList(),
                listOfNotNull(quote), emptyList(), ++clock,
            )
        }
        return id
    }

    override suspend fun finishTurn(
        turnId: Long,
        outcome: TurnOutcome,
        answer: String,
        medical: Boolean,
        usedLarge: Boolean,
        keywords: List<String>,
        shown: List<Passage>,
        candidates: List<Passage>,
    ) {
        failure?.let { throw it }
        val turn = turnList.value.firstOrNull { it.id == turnId } ?: return
        turnList.update { list ->
            list.map {
                if (it.id == turnId) {
                    it.copy(
                        outcome = outcome, answer = answer, medical = medical, usedLarge = usedLarge,
                        keywords = keywords, shown = shown, candidates = candidates,
                    )
                } else {
                    it
                }
            }
        }
        val time = ++clock
        chats.update { list -> list.map { if (it.id == turn.chatId) it.copy(updatedAt = time) else it } }
    }

    override suspend fun setGuess(turnId: Long, text: String, usedLarge: Boolean, fellBack: Boolean) {
        failure?.let { throw it }
        turnList.update { list ->
            list.map { if (it.id == turnId) it.copy(guess = text, guessLarge = usedLarge, guessFellBack = fellBack) else it }
        }
    }

    override suspend fun interruptPending(): Int {
        val pending = turnList.value.count { it.outcome == TurnOutcome.PENDING }
        turnList.update { list -> list.map { if (it.outcome == TurnOutcome.PENDING) it.copy(outcome = TurnOutcome.INTERRUPTED) else it } }
        return pending
    }

    override suspend fun delete(chatId: Long) {
        failure?.let { throw it }
        chats.update { list -> list.filterNot { it.id == chatId } }
        turnList.update { list -> list.filterNot { it.chatId == chatId } }
    }
}

class FakeFinder(var passages: List<Passage> = (1..10).map(::article)) : PassageFinder {
    var calls = 0
    /** The dispatcher the last search ran on. */
    var ranOn: ContinuationInterceptor? = null
    /** When set, searching waits for it. */
    var gate: CompletableDeferred<Unit>? = null
    /** When set, searching throws it. */
    var failure: Exception? = null
    override suspend fun find(keywords: List<String>): List<Passage> {
        calls++
        failure?.let { throw it }
        ranOn = currentCoroutineContext()[ContinuationInterceptor]
        gate?.await()
        return passages
    }
}

/**
 * Replies to the rewrite prompt (32 tokens) with [rewrite], and to the answer prompt with [answer] chunk by chunk.
 * It waits on [gate] before chunk [gateAt] and throws before chunk [failAt].
 */
class ScriptedGenerator(
    var answer: List<String> = listOf("Plants turn light into sugar [1]."),
    var rewrite: String = "keywords: photosynthesis, light\nmedical: no",
) : TextGenerator {
    val prompts = mutableListOf<String>()
    var gateAt: Int? = null
    val gate = CompletableDeferred<Unit>()
    var failAt: Int? = null

    val answerPrompts get() = prompts.filterNot { it.startsWith("Turn the question into search words") }

    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        prompts += prompt
        if (maxTokens == 32) {
            emit(rewrite)
            return@flow
        }
        answer.forEachIndexed { i, chunk ->
            if (i == gateAt) gate.await()
            if (i == failAt) throw IllegalStateException("decoder crashed")
            emit(chunk)
        }
    }
}

/** The small model always loads; the large one only when [large] is set. No embedder: ranking is lexical. */
class FakeResearchModels(val small: ScriptedGenerator = ScriptedGenerator(), var large: ScriptedGenerator? = null) : ResearchModels {
    /** When set, even the small model won't load. */
    var smallFails = false

    override suspend fun generator(deeper: Boolean): TextGenerator =
        if (deeper) {
            large ?: throw ModelUnavailableException("large model failed")
        } else {
            if (smallFails) throw ModelUnavailableException("small model failed")
            small
        }

    override suspend fun embedder(): PassageEmbedder? = null
}
