package app.granger.core.research

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Searches the library for passages matching the keywords. */
fun interface PassageFinder {
    suspend fun find(keywords: List<String>): List<Passage>
}

/** Streams a model's reply to [prompt] in chunks. */
fun interface TextGenerator {
    fun generate(prompt: String, maxTokens: Int): Flow<String>
}

/** Turns texts into vectors; [isQuery] marks the question side for models that embed queries differently. */
fun interface PassageEmbedder {
    suspend fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>
}

/** Loads the on-device models. [generator] throws [ModelUnavailableException] when the model can't load. */
interface ResearchModels {
    suspend fun generator(deeper: Boolean): TextGenerator
    suspend fun embedder(): PassageEmbedder?
}

class ModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class Stage { LOADING_MODEL, SEARCHING, RANKING, WRITING }

enum class FailReason { MODEL_UNAVAILABLE }

/**
 * One question. [quote] is the user's selection and always becomes passage [1]. With [deeperFrom] set, retrieval
 * is skipped and those passages (up to 8) go to the large model.
 */
data class ResearchRequest(
    val question: String,
    val quote: Passage? = null,
    val prior: PriorTurn? = null,
    val priorQuestion: String? = null,
    val deeperFrom: List<Passage>? = null,
    val medicalHint: Boolean = false,
)

/** How a question ended. `passages` are the numbered ones in the prompt; `candidates` are the ranked rest, kept for Think deeper. */
sealed interface Outcome {
    val medical: Boolean
    val keywords: List<String>

    data class Answered(
        val text: String,
        val passages: List<Passage>,
        val cited: Set<Int>,
        val candidates: List<Passage>,
        val usedLarge: Boolean,
        override val medical: Boolean,
        override val keywords: List<String>,
    ) : Outcome

    data class Declined(override val medical: Boolean, override val keywords: List<String>) : Outcome

    /** The reply's citations didn't check out; [text] is the raw reply, and the UI shows [passages] instead. */
    data class Unverified(
        val text: String,
        val passages: List<Passage>,
        val candidates: List<Passage>,
        val usedLarge: Boolean,
        override val medical: Boolean,
        override val keywords: List<String>,
    ) : Outcome

    data class Failed(val reason: FailReason, override val medical: Boolean, override val keywords: List<String>) : Outcome
}

sealed interface ResearchEvent {
    data class Progress(val stage: Stage) : ResearchEvent
    /** The reply so far. */
    data class Partial(val text: String) : ResearchEvent
    /** The large model wouldn't load, so the small one answers. */
    data object LargeFallback : ResearchEvent
    data class Done(val outcome: Outcome) : ResearchEvent
}

/** Question in, events out: load model, rewrite (or keyword fallback), find, rank, then decline or answer with citations. */
class ResearchPipeline(
    private val finder: PassageFinder,
    private val models: ResearchModels,
    private val config: RankingConfig = RankingConfig(),
    private val rewriteTimeoutMs: Long = 5_000,
) {
    /** [stop] is polled after each chunk of the reply; when it turns true the reply so far is checked and finished. */
    fun run(request: ResearchRequest, stop: () -> Boolean = { false }): Flow<ResearchEvent> = flow {
        emit(ResearchEvent.Progress(Stage.LOADING_MODEL))
        val deeper = request.deeperFrom != null
        val generator = loadGenerator(deeper)
        if (generator == null) {
            emit(ResearchEvent.Done(Outcome.Failed(FailReason.MODEL_UNAVAILABLE, request.medicalHint, emptyList())))
            return@flow
        }
        val usedLarge = deeper && generator.large

        val medical: Boolean
        val keywords: List<String>
        val ranked: List<Passage>
        if (request.deeperFrom != null) {
            medical = request.medicalHint
            keywords = emptyList()
            ranked = request.deeperFrom
        } else {
            val rewrite = rewrite(generator.model, request)
            medical = rewrite.medical == true || request.medicalHint
            keywords = rewrite.keywords
            emit(ResearchEvent.Progress(Stage.SEARCHING))
            val all = finder.find(keywords)
            val found = Ranking.preselect(all, keywords, config)
            emit(ResearchEvent.Progress(Stage.RANKING))
            ranked = rank(request.question, found, keywords, all)
        }

        val quote = request.quote
        if (quote == null && ranked.isEmpty()) {
            emit(ResearchEvent.Done(Outcome.Declined(medical, keywords)))
            return@flow
        }
        val pool = (listOfNotNull(quote) + ranked.filter { it != quote }).take(config.large)
        val passages = pool.take(if (usedLarge) config.large else config.small)
        val candidates = pool.drop(passages.size)

        emit(ResearchEvent.Progress(Stage.WRITING))
        val prompt = AnswerPrompt.build(request.question, passages, medical, request.prior, hasQuote = quote != null)
        val reply = generator.model.generate(prompt, ANSWER_TOKENS)
            .transformWhile { chunk ->
                emit(chunk)
                !stop()
            }
            .fold("") { soFar, chunk ->
                val text = soFar + chunk
                emit(ResearchEvent.Partial(text))
                text
            }

        val outcome = when (val check = Citations.check(reply, passages.size)) {
            is CitationCheck.Valid ->
                Outcome.Answered(reply, passages, check.cited, candidates, usedLarge, medical, keywords)
            CitationCheck.ModelDeclined -> Outcome.Declined(medical, keywords)
            CitationCheck.Invalid -> Outcome.Unverified(reply, passages, candidates, usedLarge, medical, keywords)
        }
        emit(ResearchEvent.Done(outcome))
    }

    private class Loaded(val model: TextGenerator, val large: Boolean)

    /** Null when even the small model won't load. */
    private suspend fun FlowCollector<ResearchEvent>.loadGenerator(deeper: Boolean): Loaded? {
        if (deeper) {
            try {
                return Loaded(models.generator(deeper = true), large = true)
            } catch (e: ModelUnavailableException) {
                emit(ResearchEvent.LargeFallback)
            }
        }
        return try {
            Loaded(models.generator(deeper = false), large = false)
        } catch (e: ModelUnavailableException) {
            null
        }
    }

    /**
     * The model's keywords, or the plain keyword fallback when it times out, errors, or replies with nothing usable.
     * The medical lexicon decides whenever the model didn't say.
     */
    private suspend fun rewrite(generator: TextGenerator, request: ResearchRequest): RewriteResult {
        val parsed = try {
            withTimeoutOrNull(rewriteTimeoutMs) {
                val reply = generator.generate(Rewrite.prompt(request.question, request.priorQuestion), REWRITE_TOKENS)
                    .fold("") { acc, chunk -> acc + chunk }
                Rewrite.parse(reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val keywords = parsed?.keywords.orEmpty().map(Keywords::normalize).filter { it.isNotBlank() }.distinct()
        val text = listOfNotNull(request.question, request.priorQuestion).joinToString(" ")
        if (parsed != null && keywords.isNotEmpty()) return RewriteResult(keywords, parsed.medical ?: Medical.looksMedical(text))
        return RewriteResult(Keywords.extract(text), Medical.looksMedical(text))
    }

    /** [all] is everything the search found: lexical ranking weighs words by how rare they are in it. */
    private suspend fun rank(question: String, candidates: List<Passage>, keywords: List<String>, all: List<Passage>): List<Passage> {
        if (candidates.isEmpty()) return emptyList()
        val embedder = models.embedder() ?: return Ranking.byLexical(candidates, keywords, config, corpus = all)
        val query = embedder.embed(listOf(question), isQuery = true).single()
        val vectors = embedder.embed(candidates.map { it.text }, isQuery = false)
        return Ranking.byEmbedding(candidates, query, vectors, config)
    }

    private companion object {
        const val REWRITE_TOKENS = 32
        const val ANSWER_TOKENS = 512
    }
}
