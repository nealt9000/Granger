package app.granger.reader

import app.granger.core.research.ArticleText
import app.granger.core.speech.Block
import app.granger.core.speech.BlockKind
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.Utterance
import app.granger.core.speech.UtteranceKind
import app.granger.core.speech.Utterances
import app.granger.core.zim.ZimArchive
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An article as the voice reads it: the ZIM entry's HTML through [ArticleText]'s rules, split into sentences once and
 * kept. It never touches the WebView, so the player can reopen it after process death and the reader can start the
 * voice at any block the page names.
 */
class ArticleReadSource(
    private val archive: ZimArchive,
    val packId: String,
    val path: String,
    override val title: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReadSource {
    override val key: String = ReadSourceKey.article(packId, path)
    private val lock = Mutex()
    private var loaded: List<Utterance>? = null

    private suspend fun all(): List<Utterance> = lock.withLock {
        loaded ?: withContext(io) {
            val item = archive.read(path)
            val html = item?.let { String(it.bytes, charsetOf(it.mimeType)) }.orEmpty()
            val blocks = ArticleText.blocks(html).map {
                Block(
                    it.id,
                    when {
                        it.isHeading -> BlockKind.HEADING
                        it.isCode -> BlockKind.CODE
                        it.isListItem -> BlockKind.LIST_ITEM
                        else -> BlockKind.PARAGRAPH
                    },
                    it.text,
                )
            }
            Utterances.fromBlocks(blocks) { blockId, n -> ReadAddress.Article(packId, path, blockId, n) }
        }.also { loaded = it }
    }

    override suspend fun sectionTitle(at: ReadAddress): String? {
        val list = all()
        val i = indexOf(list, at) ?: return null
        val heading = list.subList(0, i + 1).lastOrNull { it.kind == UtteranceKind.HEADING } ?: return null
        return heading.text.takeIf { it != title }
    }

    override suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance> {
        val list = all()
        val start = if (from == null) 0 else indexOf(list, from) ?: return emptyList()
        return list.subList(start, minOf(list.size, start + limit))
    }

    override suspend fun locate(address: ReadAddress): ReadAddress? {
        val list = all()
        return indexOf(list, address)?.let { list[it].address }
    }

    /** The first utterance of the whole article, or null for an article with no readable text. */
    suspend fun firstAddress(): ReadAddress.Article? = all().firstOrNull()?.address as? ReadAddress.Article

    /**
     * The sentence at [offset] (a folded character offset) into block [blockId], or the block's first sentence; a
     * block the article doesn't have gives null.
     */
    suspend fun addressAt(blockId: String, offset: Int? = null): ReadAddress.Article? {
        val inBlock = all().filter { (it.address as ReadAddress.Article).blockId == blockId }
        if (inBlock.isEmpty()) return null
        val picked = if (offset == null) inBlock.first() else inBlock.lastOrNull { it.start <= offset } ?: inBlock.first()
        return picked.address as ReadAddress.Article
    }

    /** The utterance at [address], or the first of its block when the sentence index is out of range; null when the block is gone. */
    private fun indexOf(list: List<Utterance>, address: ReadAddress): Int? {
        val a = address as? ReadAddress.Article ?: return null
        val exact = list.indexOfFirst { it.address == a }
        if (exact >= 0) return exact
        return list.indexOfFirst { (it.address as ReadAddress.Article).blockId == a.blockId }.takeIf { it >= 0 }
    }

    internal companion object {
        fun charsetOf(mimeType: String): Charset {
            val name = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(mimeType)?.groupValues?.get(1) ?: return Charsets.UTF_8
            return runCatching { Charset.forName(name.trim('"')) }.getOrDefault(Charsets.UTF_8)
        }
    }
}
