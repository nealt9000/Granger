package app.granger.core.archive

import app.granger.core.text.NaturalOrder

/**
 * One part of an item made of several (Plan IA): an issue of a comic run or a magazine, a volume. [number] counts from
 * 1 in the parts' natural order; [title] is the file's name made readable ([ArchiveParts.title]).
 */
data class Part(val file: ArchiveFile, val number: Int, val title: String) {
    val sizeBytes: Long get() = file.sizeBytes ?: 0L
}

/**
 * An item's parts (Plan IA Decisions 1 and 2): its originals of the kind [ArchiveOffers.choose] picked (every comic,
 * every uploaded PDF, every uploaded EPUB or text), never what archive.org derived from them. Tiny extras, under a
 * tenth of the median part (a cover picture's PDF, a page of errata), aren't parts. One part is an ordinary item.
 */
object ArchiveParts {
    /** A part this much smaller than the median part is an extra, not a part. */
    private const val EXTRA_SHARE = 0.10

    fun of(item: ArchiveItem, kind: TextKind, usable: List<ArchiveFile>): List<ArchiveFile> {
        val originals = usable.filter { it.source == "original" }
        val candidates = when (kind) {
            TextKind.COMIC -> originals.filter(ArchiveOffers::isComic)
            // One per document: an upload can carry the same PDF twice (an image one, and archive.org's `_text` one).
            TextKind.PDF -> originals.filter { it.format.contains("PDF") }
                .groupBy { it.name.substringBeforeLast('.').lowercase().removeSuffix("_text") }
                .values.mapNotNull { ArchiveOffers.bestPdf(it) }
            TextKind.EPUB -> originals.filter { it.format == "EPUB" }
            TextKind.TEXT -> originals.filter { it.format == "Text" }
            TextKind.OCR_TEXT -> emptyList()
        }
        if (candidates.size < 2) return candidates
        val sizes = candidates.map { it.sizeBytes ?: 0L }.sorted()
        val median = sizes[sizes.size / 2]
        return candidates.filter { (it.sizeBytes ?: 0L) >= median * EXTRA_SHARE }.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    /** [files] (in part order) as numbered, titled parts of [item]. */
    fun numbered(item: ArchiveItem, files: List<ArchiveFile>): List<Part> =
        files.mapIndexed { i, file -> Part(file, i + 1, title(item.title, file.name).ifBlank { "Part ${i + 1}" }) }

    /**
     * A part's title from its file [name]: no folder or extension, no scanner's tags ("(digital)", "(Minutemen-InnerDemons)",
     * "[c2c]"), and none of the item's own title when the name starts with it. "Batman - The Dark Knight Returns 02 (of 04)
     * (1986) (digital) (Minutemen-InnerDemons).cbr" in "Batman The Dark Knight Returns (1986)" is "02 (of 04) (1986)".
     */
    fun title(itemTitle: String, name: String): String {
        var text = name.substringAfterLast('/').substringAfterLast('\\')
        if ('.' in text) text = text.substringBeforeLast('.')
        text = text.replace('_', ' ')
        text = GROUP.replace(text) { m -> if (isTag(m.groupValues[1])) " " else m.value }
        text = dropTitle(itemTitle, text)
        return text.replace(SPACES, " ").trim(' ', '-', '–', '—', ',', '.', ':')
    }

    private val GROUP = Regex("[(\\[]([^)\\]]*)[)\\]]")
    private val SPACES = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val TAGS = setOf("digital", "scan", "scans", "c2c", "ctc", "webrip", "hd", "hq", "lq", "repack", "fixed", "dcp", "empire", "zone")

    /** A scanner's or uploader's tag: a known word, or a group's name ("Minutemen-InnerDemons", "Zone-Empire"). */
    private fun isTag(inside: String): Boolean {
        val words = WORD.findAll(inside.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return true
        if (words.any { it in TAGS }) return true
        return Regex("\\p{L}{2,}-\\p{L}{2,}").containsMatchIn(inside) && words.none { w -> w.all(Char::isDigit) }
    }

    /** [text] without the run of [itemTitle]'s words it starts with, when that's at least two words and leaves something. */
    private fun dropTitle(itemTitle: String, text: String): String {
        val titleWords = WORD.findAll(itemTitle.lowercase()).map { it.value }.toList()
        val matches = WORD.findAll(text).toList()
        var n = 0
        while (n < matches.size && n < titleWords.size && matches[n].value.lowercase() == titleWords[n]) n++
        if (n < 2 || n == matches.size) return text
        return text.substring(matches[n - 1].range.last + 1)
    }
}
