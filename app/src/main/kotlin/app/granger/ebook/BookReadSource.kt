package app.granger.ebook

import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.Sentence
import app.granger.core.speech.Sentences
import app.granger.core.speech.Utterance
import app.granger.core.speech.UtteranceKind
import app.granger.core.speech.Utterances
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.content

/**
 * A book as the voice reads it: Readium's content iterator walks the text elements from a locator, each element
 * (a paragraph, a heading, a quote) is split into sentences, and every sentence gets a locator of its own (the
 * element's, with the sentence as its text context) that the navigator can decorate and jump to. Footnotes are
 * skipped. Elements are pulled as the voice needs them, so a long book costs nothing up front.
 *
 * Addresses are `book:<id>@<element locator json>:<sentence>`; the element locator is the stable part, the sentence
 * index picks within it.
 */
@OptIn(ExperimentalReadiumApi::class)
class BookReadSource(
    private val publication: Publication,
    val bookId: String,
    override val title: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReadSource {
    override val key: String = ReadSourceKey.book(bookId)

    private val lock = Mutex()
    private var iterator: Content.Iterator? = null
    private var startedAt: String? = null                // the element locator json the iterator was opened at (null: the start)
    private val buffer = ArrayList<Utterance>()
    private var paragraphs = 0
    private var exhausted = false

    // Every sentence locator made so far, by address; kept across reopenings so the reader can decorate any sentence it is told about.
    private val locators = ConcurrentHashMap<ReadAddress, Locator>()

    /**
     * The sentence's own locator, for the navigator: the element's locator with the sentence as its text. An address
     * this source hasn't read yet (the player restored it after process death) is read now from its element.
     */
    suspend fun locatorOf(utterance: Utterance): Locator? {
        locators[utterance.address]?.let { return it }
        val a = utterance.address as? ReadAddress.Book ?: return null
        val element = parse(a.locatorJson) ?: return null
        firstElementAt(element)
        return locators[utterance.address]
    }

    override suspend fun sectionTitle(at: ReadAddress): String? {
        val a = at as? ReadAddress.Book ?: return null
        val locator = parse(a.locatorJson) ?: return null
        return locator.title?.takeIf { it.isNotBlank() && it != title }
    }

    override suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance> = lock.withLock {
        val a = from as? ReadAddress.Book
        var start = if (a == null) 0 else buffer.indexOfFirst { it.address == a }
        if (iterator == null || start < 0 || (a == null && startedAt != null)) {
            // Not in what we have: reopen the iterator at the address (or the start) and read from there.
            val locator = a?.let { parse(it.locatorJson) ?: return@withLock emptyList() }
            reset(locator, a?.locatorJson)
            start = 0
            if (a != null) {
                // The iterator usually opens on the element itself; when it opens earlier, read on until the
                // sentence turns up (bounded), rather than restart the voice from wherever the iterator began.
                fill(limit + a.sentence + 1)
                while (buffer.none { it.address == a } && !exhausted && buffer.size < REOPEN_SCAN) fill(buffer.size + limit)
                start = buffer.indexOfFirst { it.address == a }.takeIf { it >= 0 }
                    ?: buffer.indexOfFirst { (it.address as ReadAddress.Book).locatorJson == a.locatorJson }.takeIf { it >= 0 }
                    ?: 0
            }
        }
        fill(start + limit)
        return buffer.subList(start, minOf(buffer.size, start + limit)).toList()
    }

    override suspend fun locate(address: ReadAddress): ReadAddress? {
        val a = address as? ReadAddress.Book ?: return null
        return if (parse(a.locatorJson) != null) a else null
    }

    /**
     * The first sentence at or after [locator] (a visible element, a chapter start, a selection), or null when nothing
     * readable follows. A selection with highlighted text picks the sentence that text starts in. Uses an iterator of
     * its own, so the voice's place in the book is left alone.
     */
    suspend fun addressAt(locator: Locator?): ReadAddress.Book? {
        val highlight = locator?.text?.highlight?.trim()?.takeIf { it.isNotEmpty() }
        val elements = elementsAt(locator, if (highlight == null) 1 else SELECTION_SCAN)
        val first = elements.firstOrNull()?.first()?.address as? ReadAddress.Book ?: return null
        if (highlight == null) return first
        val needle = highlight.take(30)
        for (element in elements) {
            val at = element.joinToString(" ") { it.text }.indexOf(needle)
            if (at >= 0) return (element.lastOrNull { it.start <= at } ?: element.first()).address as ReadAddress.Book
        }
        return first
    }

    /**
     * The first sentence of the chapter a contents entry's [href] points into (Plan V2, `ReadAloud.start`); null when
     * the book has no such resource.
     */
    suspend fun addressAtHref(href: String): ReadAddress.Book? {
        val url = Url(href) ?: return null
        val link = publication.linkWithHref(url) ?: return null
        val locator = publication.locatorFromLink(link) ?: return null
        return addressAt(locator)
    }

    /**
     * The sentence holding [visibleText] (the first words on the page, [FirstVisibleText]) in [resource]'s chapter,
     * or null when no element there contains them. The chapter is read from its start: Readium's iterator only
     * positions itself by an element selector, and the page's own element is what can't be trusted.
     */
    suspend fun addressAtText(resource: Locator, visibleText: String): ReadAddress.Book? = withContext(io) {
        val needle = Sentences.fold(visibleText).take(NEEDLE)
        if (needle.length < NEEDLE_MIN) return@withContext null
        val start = resource.copy(locations = Locator.Locations(), text = Locator.Text())
        val it = publication.content(start)?.iterator() ?: return@withContext null
        // The chapter's readable elements, each with its folded text, then the words matched whole first and
        // shortened a word at a time: the page's first words can run on into the next paragraph.
        val elements = ArrayList<Pair<String, List<Utterance>>>()
        while (elements.size < RESOURCE_SCAN && it.hasNext()) {
            val element = it.next()
            if (!element.locator.href.isEquivalent(resource.href)) break
            val made = toUtterances(element, paragraph = 0) ?: continue
            elements += Sentences.fold((element as Content.TextElement).text) to made
        }
        for (candidate in needles(needle)) {
            for ((body, made) in elements) {
                val at = body.indexOf(candidate)
                if (at >= 0) return@withContext (made.lastOrNull { it.start <= at } ?: made.first()).address as ReadAddress.Book
            }
        }
        null
    }

    /** [needle], then [needle] cut back a word at a time while at least [NEEDLE_MIN] characters remain. */
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

    /** The utterances of the first readable element from [locator]; null when nothing readable follows. */
    private suspend fun firstElementAt(locator: Locator?): List<Utterance>? = elementsAt(locator, 1).firstOrNull()

    /** Up to [max] readable elements from [locator], each as its utterances, through a throwaway iterator. */
    private suspend fun elementsAt(locator: Locator?, max: Int): List<List<Utterance>> = withContext(io) {
        val it = publication.content(locator)?.iterator() ?: return@withContext emptyList()
        val out = ArrayList<List<Utterance>>()
        while (out.size < max && it.hasNext()) {
            toUtterances(it.next(), paragraph = out.size)?.let { made -> out += made }
        }
        out
    }

    private suspend fun reset(locator: Locator?, json: String?) {
        iterator = withContext(io) { publication.content(locator)?.iterator() }
        startedAt = json
        buffer.clear(); paragraphs = 0
        exhausted = iterator == null
    }

    /** Pulls elements until the buffer holds [count] utterances or the book ends. */
    private suspend fun fill(count: Int) {
        val it = iterator ?: return
        while (buffer.size < count && !exhausted) {
            val element = withContext(io) { if (it.hasNext()) it.next() else null }
            if (element == null) { exhausted = true; return }
            val made = toUtterances(element, paragraphs) ?: continue
            buffer += made
            paragraphs++
        }
    }

    /** A text element as sentences with their locators, or null for one the voice skips (footnotes, empty, non-text). */
    private fun toUtterances(element: Content.Element, paragraph: Int): List<Utterance>? {
        val text = element as? Content.TextElement ?: return null
        val role = text.role
        if (role is Content.TextElement.Role.Footnote || isFootnote(text.locator)) return null
        val body = Sentences.fold(text.text)
        if (body.none { c -> c.isLetterOrDigit() }) return null
        val elementJson = text.locator.toJSON().toString()
        val heading = role is Content.TextElement.Role.Heading || isHeading(text.locator)
        val sentences = if (heading) listOf(Sentence(body, 0, body.length)) else Sentences.sentences(body)
        return sentences.mapIndexed { i, s ->
            val address = ReadAddress.Book(bookId, elementJson, i)
            val last = i == sentences.lastIndex
            val pause = when { heading -> Utterances.HEADING_PAUSE_MS; last -> Utterances.PARAGRAPH_PAUSE_MS; else -> Utterances.SENTENCE_PAUSE_MS }
            locators[address] = text.locator.copy(
                text = Locator.Text(before = body.substring(0, s.start).takeLast(CONTEXT), highlight = s.text, after = body.substring(s.end).take(CONTEXT)),
            )
            Utterance(address, if (heading) UtteranceKind.HEADING else UtteranceKind.SENTENCE, s.text, paragraph, pause, s.start, s.end)
        }
    }

    /** Readium flags `epub:type="footnote"` on the element itself; a paragraph inside an `<aside>` is a footnote's body too. */
    private fun isFootnote(locator: Locator): Boolean {
        val selector = selectorOf(locator) ?: return false
        return selector.contains("aside") || selector.contains("footnote") || selector.contains("endnote")
    }

    /** `h1`–`h6` by the element's own tag, for a parser that leaves the role as body. */
    private fun isHeading(locator: Locator): Boolean {
        val last = selectorOf(locator)?.substringAfterLast('>')?.trim()?.substringBefore(':')?.substringBefore('.')?.substringBefore('[') ?: return false
        return last.matches(Regex("h[1-6]"))
    }

    private fun selectorOf(locator: Locator): String? = locator.locations.otherLocations["cssSelector"] as? String

    private fun parse(json: String): Locator? = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    private companion object {
        const val CONTEXT = 60
        /** How many elements after a selection's locator are searched for its text (a selection's locator can point at its page, not its paragraph). */
        const val SELECTION_SCAN = 8
        /** How many utterances past a reopened iterator's start are searched for the asked-for sentence. */
        const val REOPEN_SCAN = 600
        /** How many elements of a chapter are searched for the page's first words, and how many of those words count. */
        const val RESOURCE_SCAN = 3_000
        const val NEEDLE = 40
        const val NEEDLE_MIN = 12
    }
}
