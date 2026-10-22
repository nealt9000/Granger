package app.granger.core.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Plain text still being written (a Research answer as it streams, Plan V2). [append] gives the text so far (it only
 * grows), [complete] closes it. Sentences are handed out as they close; the last fragment waits for whitespace after
 * it or for [complete], so a sentence is never cut in two. [utterances] suspends while nothing new is ready and the
 * text is still open, and returns empty only once the source is complete and spent, so the player's queue waits
 * for the model instead of ending the read. [preface] is spoken first (the medical banner).
 */
class StreamingTextReadSource(override val key: String, override val title: String, private val preface: String? = null) : ReadSource {
    @Volatile private var text = ""
    @Volatile private var complete = false
    @Volatile private var ready: List<Utterance> = emptyList()
    private val version = MutableStateFlow(0)

    val size: Int get() = ready.size
    val isComplete: Boolean get() = complete

    fun append(textSoFar: String) {
        if (complete) return
        text = textSoFar
        rebuild()
    }

    fun complete() {
        complete = true
        rebuild()
    }

    @Synchronized
    private fun rebuild() {
        val whole = listOfNotNull(preface?.takeIf { it.isNotBlank() }, text).joinToString("\n\n")
        val blocks = whole.split(Regex("\\n\\s*\\n")).mapIndexed { i, para -> Block(i.toString(), BlockKind.PARAGRAPH, para) }.filter { it.text.isNotBlank() }
        val made = Utterances.fromBlocks(blocks) { blockId, n -> ReadAddress.Text(key, blockId.toInt(), n) }
        // The last sentence may still be growing unless the text ends with whitespace (or is finished).
        val settled = complete || text.isEmpty() || text.last().isWhitespace()
        ready = if (settled || made.isEmpty()) made else made.dropLast(1)
        version.value++
    }

    override suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance> {
        while (true) {
            val v = version.value
            val all = ready
            val start = if (from == null) 0 else all.indexOfFirst { it.address == from }
            if (start in all.indices) return all.subList(start, minOf(all.size, start + limit))
            if (complete) return emptyList()
            version.first { it != v }        // more text, or the end
        }
    }
}
