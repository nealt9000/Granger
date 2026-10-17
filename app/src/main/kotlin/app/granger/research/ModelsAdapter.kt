package app.granger.research

import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LlmSize
import app.granger.core.ai.ModelLoadException
import app.granger.core.ai.ModelManager
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.research.ModelUnavailableException
import app.granger.core.research.PassageEmbedder
import app.granger.core.research.ResearchModels
import app.granger.core.research.TextGenerator
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The model files on disk: INSTALLED model records, each given the role of the catalog entry with the same id.
 * When a role has several, the newest wins.
 */
fun installedModelsOf(
    records: List<DownloadRecord>,
    models: List<ModelEntry>,
    exists: (File) -> Boolean = File::isFile,
): InstalledModels {
    val roles = models.associate { it.id to it.role }
    val newest = records
        .filter { it.kind == ItemKind.MODEL && it.state == DownloadState.INSTALLED && it.itemId in roles }
        .filter { exists(File(it.filePath)) }
        .groupBy { roles.getValue(it.itemId) }
        .mapValues { (_, list) -> File(list.maxBy { it.updatedAt }.filePath) }
    return InstalledModels(newest[ModelRole.SMALL], newest[ModelRole.LARGE], newest[ModelRole.EMBEDDER])
}

/**
 * Research's view of [ModelManager]. Every generation and embedding holds the models, so a release asked for
 * meanwhile (the app going to the background) waits until the work is done.
 */
class ModelsAdapter(
    private val manager: ModelManager,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ResearchModels {
    override suspend fun generator(deeper: Boolean): TextGenerator {
        val size = if (deeper) LlmSize.LARGE else LlmSize.SMALL
        // Load now, so a model that won't load is reported here rather than mid-answer.
        held { engine(size) }
        return TextGenerator { prompt, maxTokens ->
            flow {
                val letGo = manager.hold()
                try {
                    // Fetched per reply: a release between replies closes the engine, and this loads it again.
                    emitAll(engine(size).generate(prompt, maxTokens))
                } finally {
                    letGo()
                }
            }
        }
    }

    override suspend fun embedder(): PassageEmbedder? {
        held { manager.embedder() } ?: return null
        return PassageEmbedder { texts, isQuery ->
            held {
                val embedder = checkNotNull(manager.embedder()) { "The embedder is no longer available" }
                withContext(io) { embedder.embed(texts, isQuery) }
            }
        }
    }

    private suspend fun engine(size: LlmSize) = try {
        manager.engine(size)
    } catch (e: ModelLoadException) {
        throw ModelUnavailableException("The $size model won't load", e)
    }

    private suspend fun <T> held(block: suspend () -> T): T {
        val letGo = manager.hold()
        try {
            return block()
        } finally {
            letGo()
        }
    }
}
