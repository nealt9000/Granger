package app.granger.core.books

internal const val MIN_CHUNK = 400
internal const val MAX_CHUNK = 2000

/**
 * Packs [blocks] (paragraphs, in reading order) into search-sized windows: a window closes once it reaches
 * [MIN_CHUNK] characters, and none is longer than [MAX_CHUNK]. Blank blocks are skipped. Deterministic.
 */
internal fun windows(blocks: Sequence<String>): List<String> {
    val texts = ArrayList<String>()
    var buffer = ""
    for (own in blocks) {
        if (own.isEmpty()) continue
        for (piece in cutAtSpaces(own, MAX_CHUNK)) {
            buffer = if (buffer.isEmpty()) piece else "$buffer\n$piece"
            if (buffer.length > MAX_CHUNK) {
                val parts = cutAtSpaces(buffer, MAX_CHUNK)
                texts.addAll(parts.dropLast(1))
                buffer = parts.last()
            }
            if (buffer.length >= MIN_CHUNK) {
                texts.add(buffer)
                buffer = ""
            }
        }
    }
    if (buffer.isNotEmpty()) texts.add(buffer)
    return texts
}

/** [texts] as chunks of [href], each with the share of the text before it as its progression. */
internal fun chunked(href: String, texts: List<String>): List<TextChunk> {
    val total = texts.sumOf { it.length }.toDouble()
    var before = 0
    return texts.mapIndexed { i, t -> TextChunk(href, i, before / total, t).also { before += t.length } }
}
