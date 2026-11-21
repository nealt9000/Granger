package app.granger.readaloud

import app.granger.core.books.EpubResources
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadChapter
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.Sentence
import app.granger.core.speech.Sentences
import app.granger.core.speech.Utterance
import app.granger.core.speech.UtteranceKind
import app.granger.core.speech.Utterances
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A contents entry as the reader's publication lists it: its title, its link (`OEBPS/c001.xhtml#ch3`) and its nesting. */
data class EpubTocEntry(val title: String, val href: String, val depth: Int)

/**
 * A book as the iPhone's voice reads it (Plan I5 Task 8). Android's `BookReadSource` walks Readium Kotlin's content
 * iterator; Readium Swift's is async and lives on the Swift side, so the iPhone walks the EPUB's XHTML here instead, with
 * the same rules: the readable block elements in reading order (paragraphs, headings, list items, quotes), footnotes
 * and asides skipped, a heading read whole, every other block split into [Sentences], the pauses of [Utterances].
 *
 * Every sentence has a Readium locator ([locatorJson]): its element's (`href`, `progression` within the file and a
 * `cssSelector`) with the sentence as `text`, which Readium's navigator decorates and goes to on either phone (its
 * scripts find the text by quote, the selector only narrowing where to look). Addresses are
 * `book:<id>@<element locator json>:<sentence>`, as Android's; the element locator is made the same way each time, so a
 * saved place is found again. Files are read as the voice needs them.
 *
 * [toc] and [readingOrder] come from the reader's publication (Readium Swift's hrefs, used in the locators); without a
 * reading order the spine's zip paths stand in.
 */
class EpubReadSource(
    val bookId: String,
    override val title: String,
    private val epubPath: String,
    private val toc: List<EpubTocEntry>,
    readingOrder: List<String> = emptyList(),
    private val io: CoroutineDispatcher = app.granger.screens.ioDispatcher,
) : ReadSource {
    override val key: String = ReadSourceKey.book(bookId)

    private val lock = Mutex()
    private val spine: List<String> by lazy { EpubResources.spine(epubPath).orEmpty() }
    /** The hrefs locators carry, by spine position: the reader's own where it gave them, else the zip paths. */
    private val hrefs: List<String> by lazy {
        if (readingOrder.isEmpty()) spine
        else spine.map { path -> readingOrder.firstOrNull { decode(it) == path } ?: path }
    }
    private val loaded = HashMap<Int, Resource>()

    /** One file's readable blocks, its sentences, and which block each element id leads to. */
    private class Resource(val blocks: List<Block>, val utterances: List<Utterance>, val anchors: Map<String, Int>)

    /** A readable element: its locator (no text), its folded text, whether it's a heading. */
    private class Block(val json: String, val text: String, val heading: Boolean)

    /** Where a sentence is in the book: the file in the reading order, the block in the file, the sentence. */
    private data class Place(val order: Int, val block: Int, val sentence: Int) : Comparable<Place> {
        override fun compareTo(other: Place): Int = compareValuesBy(this, other, Place::order, Place::block, Place::sentence)
    }

    private class Entry(val title: String, val depth: Int, val order: Int, val fragment: String?)

    private val entries: List<Entry> by lazy {
        toc.map { e ->
            val path = decode(e.href.substringBefore('#'))
            Entry(e.title.trim(), e.depth, orderOf(path), e.href.substringAfter('#', "").ifEmpty { null })
        }
    }

    private fun orderOf(pathOrHref: String): Int {
        val path = decode(pathOrHref.substringBefore('#'))
        return spine.indexOf(path).takeIf { it >= 0 } ?: hrefs.indexOf(pathOrHref.substringBefore('#'))
    }

    // ---- ReadSource ----

    override suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance> = lock.withLock {
        val start = if (from == null) firstPlace() else placeOf(from) ?: return@withLock emptyList()
        start ?: return@withLock emptyList()
        val out = ArrayList<Utterance>()
        var order = start.order
        var first = true
        while (out.size < limit && order < spine.size) {
            val res = resource(order)
            val list = res.utterances
            val from0 = if (first) indexIn(res, start) else 0
            first = false
            if (from0 >= 0) out += list.subList(from0, list.size).take(limit - out.size)
            order++
        }
        out
    }

    override suspend fun locate(address: ReadAddress): ReadAddress? = lock.withLock {
        val place = placeOf(address) ?: return@withLock null
        val res = resource(place.order)
        res.utterances.getOrNull(indexIn(res, place))?.address
    }

    override suspend fun chapters(): List<ReadChapter> = toc.map { ReadChapter(it.title.trim(), it.depth) }

    override suspend fun chapterStart(index: Int): ReadAddress.Book? = lock.withLock { entryStart(index) }

    override suspend fun chapterAt(at: ReadAddress): Int? = lock.withLock { chapterIndexAt(at) }

    override suspend fun compare(a: ReadAddress, b: ReadAddress): Int? = lock.withLock {
        val pa = placeOf(a) ?: return@withLock null
        val pb = placeOf(b) ?: return@withLock null
        pa.compareTo(pb)
    }

    override suspend fun sectionTitle(at: ReadAddress): String? = lock.withLock {
        val place = placeOf(at) ?: return@withLock null
        val entry = chapterIndexAt(at)?.let { entries[it] }
        val byFile = entries.firstOrNull { it.order == place.order && it.title.isNotEmpty() }
        (entry?.title?.takeIf { it.isNotEmpty() } ?: byFile?.title)?.takeIf { it != title }
    }

    // ---- what the reader asks ----

    /**
     * The sentence's own locator for the navigator: its element's with the sentence as `text.highlight` and up to
     * [CONTEXT] characters either side. Null for an address this book doesn't have.
     */
    suspend fun locatorJson(utterance: Utterance): String? = lock.withLock {
        val a = utterance.address as? ReadAddress.Book ?: return@withLock null
        val place = placeOf(a) ?: return@withLock null
        val block = resource(place.order).blocks.getOrNull(place.block) ?: return@withLock null
        val start = utterance.start.coerceIn(0, block.text.length)
        val end = utterance.end.coerceIn(start, block.text.length)
        val element = Json.parseToJsonElement(block.json).jsonObject
        JsonObject(element + ("text" to buildJsonObject {
            put("before", block.text.substring(0, start).takeLast(CONTEXT))
            put("highlight", block.text.substring(start, end))
            put("after", block.text.substring(end).take(CONTEXT))
        })).toString()
    }

    /**
     * The sentence holding [visibleText] (the page's first words) in the file [href] points into, else that file's first
     * sentence, else null. Words matched whole first, then shortened a word at a time, as Android's `addressAtText`.
     */
    suspend fun addressAtText(href: String, visibleText: String?): ReadAddress.Book? = lock.withLock {
        val order = orderOf(href).takeIf { it >= 0 } ?: return@withLock null
        val res = resource(order)
        val needle = visibleText?.let { Sentences.fold(it).take(NEEDLE) }
        if (needle != null && needle.length >= NEEDLE_MIN) {
            for (candidate in needles(needle)) {
                for ((i, block) in res.blocks.withIndex()) {
                    val at = block.text.indexOf(candidate)
                    if (at < 0) continue
                    val inBlock = res.utterances.filter { (it.address as ReadAddress.Book).locatorJson == block.json }
                    if (inBlock.isEmpty()) continue
                    return@withLock (inBlock.lastOrNull { it.start <= at } ?: inBlock.first()).address as ReadAddress.Book
                }
            }
        }
        (res.utterances.firstOrNull() ?: firstFrom(order + 1))?.address as? ReadAddress.Book
    }

    /** The first sentence of the file a contents entry's [href] points into (or of its anchored element). */
    suspend fun addressAtHref(href: String): ReadAddress.Book? = lock.withLock {
        val order = orderOf(href).takeIf { it >= 0 } ?: return@withLock null
        startOf(order, href.substringAfter('#', "").ifEmpty { null })
    }

    /** [at], or past the book's front matter when it falls in it: Android's `BookReadSource.pastFrontMatter`. */
    suspend fun pastFrontMatter(at: ReadAddress.Book): ReadAddress.Book = lock.withLock {
        val here = placeOf(at) ?: return@withLock at
        val placed = entries.withIndex().filter { it.value.order >= 0 }
        if (placed.isEmpty()) return@withLock at
        val firstOrder = placed.minOf { it.value.order }
        val entry = chapterIndexAt(at)
        val front = here.order < firstOrder || (entry != null && isFrontMatter(entries[entry].title))
        if (!front) return@withLock at
        val next = placed.firstOrNull { (i, e) ->
            e.order >= here.order && i != entry && !isFrontMatter(e.title) && (entryPlace(i)?.let { it > here } ?: false)
        } ?: return@withLock at
        entryStart(next.index) ?: at
    }

    /** Where Listen starts a book from nowhere in it: its first sentence past the front matter. */
    suspend fun firstAfterFrontMatter(): ReadAddress.Book? {
        val first = lock.withLock { firstPlace()?.let { p -> resource(p.order).utterances.getOrNull(indexIn(resource(p.order), p))?.address as? ReadAddress.Book } }
            ?: return null
        return pastFrontMatter(first)
    }

    // ---- places ----

    private fun firstPlace(): Place? {
        for (order in spine.indices) if (resource(order).utterances.isNotEmpty()) return Place(order, 0, 0)
        return null
    }

    private fun firstFrom(order: Int): Utterance? {
        for (o in order until spine.size) resource(o).utterances.firstOrNull()?.let { return it }
        return null
    }

    /** The utterance index in [res] at [place]: the exact sentence, else its block's first, else the first after it. */
    private fun indexIn(res: Resource, place: Place): Int {
        val exact = res.utterances.indexOfFirst { blockOf(res, it) == place.block && it.address.sentence == place.sentence }
        if (exact >= 0) return exact
        val sameBlock = res.utterances.indexOfFirst { blockOf(res, it) == place.block }
        if (sameBlock >= 0) return sameBlock
        return res.utterances.indexOfFirst { blockOf(res, it) > place.block }.takeIf { it >= 0 } ?: res.utterances.size
    }

    private fun blockOf(res: Resource, u: Utterance): Int {
        val json = (u.address as ReadAddress.Book).locatorJson
        return res.blocks.indexOfFirst { it.json == json }
    }

    private fun placeOf(at: ReadAddress): Place? {
        val a = at as? ReadAddress.Book ?: return null
        val locator = runCatching { Json.parseToJsonElement(a.locatorJson).jsonObject }.getOrNull() ?: return null
        val href = locator["href"]?.jsonPrimitive?.content ?: return null
        val order = orderOf(href).takeIf { it >= 0 } ?: return null
        val res = resource(order)
        val block = res.blocks.indexOfFirst { it.json == a.locatorJson }.takeIf { it >= 0 } ?: run {
            // An element locator made elsewhere (Readium's own): by its selector, else by its progression in the file.
            val locations = locator["locations"]?.jsonObject
            val selector = locations?.get("cssSelector")?.jsonPrimitive?.content
            val bySelector = selector?.let { s -> res.blocks.indexOfFirst { selectorOf(it.json) == s } }?.takeIf { it >= 0 }
            bySelector ?: locations?.get("progression")?.jsonPrimitive?.doubleOrNull?.let { p ->
                (p * res.blocks.size).toInt().coerceIn(0, (res.blocks.size - 1).coerceAtLeast(0))
            } ?: 0
        }
        return Place(order, block, a.sentence)
    }

    private fun selectorOf(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["locations"]?.jsonObject?.get("cssSelector")?.jsonPrimitive?.content
    }.getOrNull()

    private fun startOf(order: Int, fragment: String?): ReadAddress.Book? {
        val res = resource(order)
        val block = fragment?.let { res.anchors[it] }
        val u = if (block != null) res.utterances.firstOrNull { blockOf(res, it) >= block } else res.utterances.firstOrNull()
        return (u ?: firstFrom(order + 1))?.address as? ReadAddress.Book
    }

    private fun entryStart(index: Int): ReadAddress.Book? {
        val e = entries.getOrNull(index) ?: return null
        if (e.order < 0) return null
        return startOf(e.order, e.fragment)
    }

    private fun entryPlace(index: Int): Place? {
        val e = entries[index]
        if (e.order < 0) return null
        if (e.fragment == null) return Place(e.order, 0, 0)
        return entryStart(index)?.let(::placeOf)
    }

    /** The last contents entry (in reading order) starting at or before [at]; of entries at the same place, the deepest. */
    private fun chapterIndexAt(at: ReadAddress): Int? {
        val here = placeOf(at) ?: return null
        var best: Int? = null
        var bestPlace: Place? = null
        for (i in entries.indices) {
            val p = entryPlace(i) ?: continue
            if (p <= here && (bestPlace == null || p >= bestPlace)) { best = i; bestPlace = p }
        }
        return best
    }

    // ---- reading a file ----

    private fun resource(order: Int): Resource = loaded.getOrPut(order) { parse(order) }

    private fun parse(order: Int): Resource {
        val path = spine.getOrNull(order) ?: return Resource(emptyList(), emptyList(), emptyMap())
        val bytes = EpubResources.read(epubPath, path) ?: return Resource(emptyList(), emptyList(), emptyMap())
        val doc = runCatching { Ksoup.parse(bytes.decodeToString()) }.getOrNull() ?: return Resource(emptyList(), emptyList(), emptyMap())
        val body = doc.body()
        val found = ArrayList<Pair<Element, Boolean>>()
        val anchors = HashMap<String, Int>()
        val pending = ArrayList<String>()
        fun visit(el: Element) {
            if (isSkipped(el)) return
            if (el.id().isNotEmpty()) pending += el.id()
            val tag = el.tagName().lowercase()
            val heading = tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6'
            if ((tag in BLOCKS || heading) && el.children().none { isBlockLike(it) }) {
                if (Sentences.fold(el.text()).any { it.isLetterOrDigit() }) {
                    for (id in pending) anchors[id] = found.size
                    el.getAllElements().forEach { inner -> if (inner.id().isNotEmpty()) anchors.getOrPut(inner.id()) { found.size } }
                    pending.clear()
                    found += el to heading
                }
                return
            }
            for (child in el.children()) visit(child)
        }
        visit(body)
        val href = hrefs.getOrElse(order) { path }
        val fileTitle = entries.firstOrNull { it.order == order && it.title.isNotEmpty() }?.title
        val blocks = found.mapIndexed { i, (el, heading) ->
            val json = buildJsonObject {
                put("href", href)
                put("type", "application/xhtml+xml")
                if (fileTitle != null) put("title", fileTitle)
                putJsonObject("locations") {
                    put("progression", if (found.size <= 1) 0.0 else i.toDouble() / found.size)
                    put("cssSelector", el.cssSelector())
                }
            }.toString()
            Block(json, Sentences.fold(el.text()), heading)
        }
        val utterances = ArrayList<Utterance>()
        for ((i, block) in blocks.withIndex()) {
            val sentences = if (block.heading) listOf(Sentence(block.text, 0, block.text.length)) else Sentences.sentences(block.text)
            val paragraph = order * PARAGRAPHS_PER_FILE + i
            sentences.forEachIndexed { n, s ->
                val last = n == sentences.lastIndex
                val pause = when { block.heading -> Utterances.HEADING_PAUSE_MS; last -> Utterances.PARAGRAPH_PAUSE_MS; else -> Utterances.SENTENCE_PAUSE_MS }
                utterances += Utterance(
                    ReadAddress.Book(bookId, block.json, n), if (block.heading) UtteranceKind.HEADING else UtteranceKind.SENTENCE,
                    s.text, paragraph, pause, s.start, s.end,
                )
            }
        }
        return Resource(blocks, utterances, anchors)
    }

    private fun isBlockLike(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        return tag in BLOCKS || tag in CONTAINERS || (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6')
    }

    /** Footnotes and asides aren't read, nor what a reader never sees (scripts, styles, the page's own navigation). */
    private fun isSkipped(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag in SKIPPED) return true
        val type = el.attr("epub:type").lowercase() + " " + el.attr("role").lowercase()
        if ("footnote" in type || "endnote" in type || "rearnote" in type || "noteref" in type) return true
        val cls = el.className().lowercase()
        return "footnote" in cls || "endnote" in cls
    }

    private fun needles(needle: String): List<String> {
        val out = mutableListOf(needle)
        var cut = needle
        while (true) {
            val space = cut.lastIndexOf(' ')
            if (space < NEEDLE_MIN) break
            cut = cut.substring(0, space)
            out += cut
        }
        return out
    }

    companion object {
        /** Contents titles that name front matter, as Android's `BookReadSource.isFrontMatter`. */
        private val FRONT_MATTER = setOf(
            "front matter", "cover", "title page", "contents", "table of contents", "list of illustrations", "illustrations",
        )

        fun isFrontMatter(title: String): Boolean = title.trim().trimEnd('.', ':').lowercase() in FRONT_MATTER

        private val BLOCKS = setOf("p", "li", "blockquote", "pre", "dd", "dt", "figcaption", "td", "th", "caption", "div", "address")
        private val CONTAINERS = setOf("section", "article", "ul", "ol", "dl", "table", "tbody", "thead", "tr", "figure", "header", "footer", "main")
        private val SKIPPED = setOf("aside", "script", "style", "nav", "head", "template", "svg", "math")
        private const val PARAGRAPHS_PER_FILE = 100_000
        private const val CONTEXT = 60
        private const val NEEDLE = 40
        private const val NEEDLE_MIN = 12

        /** A Readium href with its percent escapes undone, as the zip names its files. */
        internal fun decode(href: String): String {
            if ('%' !in href) return href
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < href.length) {
                val c = href[i]
                if (c == '%' && i + 2 < href.length && href.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                    bytes += href.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
                } else {
                    c.toString().encodeToByteArray().forEach { bytes += it }; i++
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
