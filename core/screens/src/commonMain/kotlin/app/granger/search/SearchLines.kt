package app.granger.search

import app.granger.UiText
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.ArtistHit
import app.granger.core.library.Audiobook
import app.granger.core.library.BookHit
import app.granger.core.library.MediaNames
import app.granger.core.library.SearchHit
import app.granger.core.library.SearchText
import app.granger.core.library.Track
import app.granger.core.research.BestMatches
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.core.text.Spellings

/**
 * Search's rows in words, shared with the iPhone since Plan I7 Task 7c (moved out of Android's `SearchRows`, which
 * hands on to these): the line under each music, audiobook and book result, the Show all row, the title-only note,
 * which article snippets are worth showing, and a Best match's sentence, bold words, source and kind.
 */
object SearchLines {
    /** A title as words, to tell the same article in two places (online and on the phone). */
    fun titleKey(title: String): String = title.lowercase().replace('_', ' ').replace(SPACES, " ").trim()

    fun artist(a: ArtistHit): UiText = UiText.Plural("search_artist_line", a.albumCount, a.albumCount)

    fun album(a: AlbumSummary): UiText = UiText.Res("search_album_line", shownName(a.albumArtist))

    fun song(t: Track): UiText = UiText.Res(
        "search_song_line",
        MediaNames.trackArtistsOf(t).joinToString(", ").ifBlank { null }?.let(::shownName) ?: UiText.Res("music_unknown_artist"),
        shownName(Albums.title(t)),
    )

    fun audiobook(b: Audiobook): UiText {
        val author: UiText = b.author.ifBlank { null }?.let { UiText.Raw(it) } ?: UiText.Res("audiobooks_unknown_author")
        return if (b.narrator.isBlank()) author else UiText.Res("search_audiobook_read_by", author, b.narrator)
    }

    /**
     * A name as shown: the library's "Unknown artist", "Unknown album" and "Unknown genre" placeholders are data (stored,
     * sorted and compared in English), so only their display goes through the words (Android's `shownNameText`).
     */
    fun shownName(name: String): UiText = when (name) {
        Albums.UNKNOWN_ARTIST -> UiText.Res("music_unknown_artist")
        Albums.UNKNOWN_ALBUM -> UiText.Res("music_unknown_album")
        MediaNames.UNKNOWN_GENRE -> UiText.Res("music_unknown_genre")
        else -> UiText.Raw(name)
    }

    /** A [capped] group may hold more than were fetched, so it doesn't claim to show them all. */
    fun showAll(group: SearchGroup, count: Int, capped: Boolean): UiText = if (capped) {
        when (group) {
            SearchGroup.TITLES -> UiText.Plural("search_show_first_titles", count, count)
            SearchGroup.ARTISTS -> UiText.Plural("search_show_first_artists", count, count)
            SearchGroup.ALBUMS -> UiText.Plural("search_show_first_albums", count, count)
            SearchGroup.SONGS -> UiText.Plural("search_show_first_songs", count, count)
            SearchGroup.AUDIOBOOKS -> UiText.Plural("search_show_first_audiobooks", count, count)
            SearchGroup.FULL_TEXT, SearchGroup.BEST -> UiText.Plural("search_show_first_articles", count, count)
            SearchGroup.BOOKS -> UiText.Plural("search_show_first_books", count, count)
        }
    } else {
        when (group) {
            SearchGroup.TITLES -> UiText.Plural("search_show_all_titles", count, count)
            SearchGroup.ARTISTS -> UiText.Plural("search_show_all_artists", count, count)
            SearchGroup.ALBUMS -> UiText.Plural("search_show_all_albums", count, count)
            SearchGroup.SONGS -> UiText.Plural("search_show_all_songs", count, count)
            SearchGroup.AUDIOBOOKS -> UiText.Plural("search_show_all_audiobooks", count, count)
            SearchGroup.FULL_TEXT, SearchGroup.BEST -> UiText.Plural("search_show_all_articles", count, count)
            SearchGroup.BOOKS -> UiText.Plural("search_show_all_books", count, count)
        }
    }

    fun showFewer(group: SearchGroup): UiText = when (group) {
        SearchGroup.TITLES -> UiText.Res("search_show_fewer_titles")
        SearchGroup.ARTISTS -> UiText.Res("search_show_fewer_artists")
        SearchGroup.ALBUMS -> UiText.Res("search_show_fewer_albums")
        SearchGroup.SONGS -> UiText.Res("search_show_fewer_songs")
        SearchGroup.AUDIOBOOKS -> UiText.Res("search_show_fewer_audiobooks")
        SearchGroup.FULL_TEXT, SearchGroup.BEST -> UiText.Res("search_show_fewer_articles")
        SearchGroup.BOOKS -> UiText.Res("search_show_fewer_books")
    }

    /** Best matches' own foot row: "Show more matches", or "Show fewer matches" once open. */
    fun bestMore(open: Boolean): UiText = if (open) UiText.Res("search_best_fewer") else UiText.Res("search_best_more")

    /**
     * A book pack's text hit as a book's ([SearchViewModel], Plan KB2 Task 4.5): no place of its own (an empty href opens
     * the book where the reader left it), and its snippet only when it shows the query.
     */
    fun bookHitOf(hit: SearchHit, query: String): BookHit = BookHit(
        bookId = hit.packId, bookTitle = hit.title, author = hit.author, href = "", progression = 0.0,
        snippet = hit.snippet?.takeIf { snippetMatches(it, query) }.orEmpty(),
    )

    /**
     * A book result's line under its title: the author, and "3 matches" when the book matched in more than one place;
     * null when it has neither.
     */
    fun bookLine(hit: BookHit): UiText? = when {
        hit.matches > 1 && hit.author.isNotBlank() -> UiText.Plural("search_book_matches_by", hit.matches, hit.matches, hit.author)
        hit.matches > 1 -> UiText.Plural("search_book_matches", hit.matches, hit.matches)
        hit.author.isNotBlank() -> UiText.Raw(hit.author)
        else -> null
    }

    /** A PDF hit's page under its title ("p. 12"); null for any other place. */
    fun bookPage(hit: BookHit): UiText? = app.granger.core.books.PdfText.pageOf(hit.href)?.let { UiText.Res("search_page", it) }

    /**
     * Under the results: the packs searched by title only (no full-text index), three named and the rest counted; null
     * when there are none.
     */
    fun titleOnly(titles: List<String>): UiText? {
        if (titles.isEmpty()) return null
        val named = titles.take(3).joinToString(", ")
        val rest = titles.size - 3
        return if (rest > 0) UiText.Plural("search_title_only_more", rest, named, rest)
        else UiText.Plural("search_title_only", titles.size, named)
    }

    /**
     * Whether an article's [snippet] shows the match, so it's worth showing: it holds one of [query]'s words, case and
     * accents aside, or a libkiwix `<b>` mark (which also marks a stemmed match, "apocalyptic" for "apocalypse"). A
     * snippet of page chrome (category footers, menus, profile stats) has neither, and the row shows title and pack only
     * (Plan U3b Decision 10).
     */
    fun snippetMatches(snippet: String, query: String): Boolean {
        if (BOLD.containsMatchIn(snippet)) return true
        val text = SearchText.fold(plainText(snippet))
        return SearchText.words(query).any { it in text }
    }

    /** A snippet's words without its tags and with its few entities read (the iPhone draws snippets as plain text). */
    fun plainText(html: String): String =
        ENTITIES.entries.fold(TAG.replace(html, " ").replace(SPACES, " ").trim()) { text, (entity, char) -> text.replace(entity, char) }

    /** A snippet's `<b>` marks as ranges of [plainSnippet]'s text: what the iPhone draws bold. */
    fun snippetBold(html: String): Pair<String, List<IntRange>> {
        val out = StringBuilder()
        val bold = ArrayList<IntRange>()
        var start = -1
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i).takeIf { it >= 0 } ?: html.lastIndex
                val tag = html.substring(i + 1, end).trim().lowercase()
                if (tag == "b" || tag.startsWith("b ")) start = out.length
                else if (tag == "/b" && start >= 0) { if (out.length > start) bold += start until out.length; start = -1 }
                i = end + 1
                continue
            }
            if (c == '&') {
                val entity = ENTITIES.keys.firstOrNull { html.startsWith(it, i) }
                if (entity != null) { out.append(ENTITIES.getValue(entity)); i += entity.length; continue }
            }
            out.append(c)
            i++
        }
        return out.toString() to bold
    }

    /** A Best match's line: its sentence (else the passage's first 220 characters) in quotes, as Android's row shows it. */
    fun matchLine(match: BestMatches.Match): String {
        val p = match.passage
        val sentence = match.sentence?.let { p.text.substring(it).trim() } ?: p.text.take(220)
        return "“$sentence”"
    }

    /**
     * Under a Best match's sentence: its pack, else the book's collection; a media card's "Your music" or "Your
     * audiobooks" in the phone's words (the card's own `packTitle` stays English: it's what Ask reads).
     */
    fun matchSource(p: Passage): UiText = when {
        p.kind == SourceKind.MEDIA && p.sourceId.startsWith(ALBUM) -> UiText.Res("search_your_music")
        p.kind == SourceKind.MEDIA -> UiText.Res("search_your_audiobooks")
        else -> UiText.Raw(p.packTitle.ifBlank { p.meta.collection })
    }

    /** What a Best match is, for its icon and its spoken label: an article, a book, or an album or audiobook. */
    enum class MatchKind { ARTICLE, BOOK, MUSIC, AUDIOBOOK }

    fun matchKind(p: Passage): MatchKind = when (p.kind) {
        SourceKind.BOOK -> MatchKind.BOOK
        SourceKind.MEDIA -> if (p.sourceId.startsWith(ALBUM)) MatchKind.MUSIC else MatchKind.AUDIOBOOK
        else -> MatchKind.ARTICLE
    }

    /** The kind icon's spoken label (TalkBack, VoiceOver). */
    fun kindLabel(kind: MatchKind): UiText = UiText.Res(
        when (kind) {
            MatchKind.ARTICLE -> "search_kind_article"
            MatchKind.BOOK -> "search_kind_book"
            MatchKind.MUSIC -> "search_kind_music"
            MatchKind.AUDIOBOOK -> "search_kind_audiobook"
        },
    )

    /**
     * The words of [sentence] that match [keywords], as ranges to draw bold (Plan SQ Task 5.1): a word matches when, case
     * and accents aside, it is a keyword's word or begins with one of three letters or more ("burns" for "burn").
     */
    fun highlights(sentence: String, keywords: List<String>): List<IntRange> {
        // Either spelling of a word is bold ("gray" in the text for "grey" searched).
        val wanted = keywords.flatMap { SearchText.words(it) }.flatMap { Spellings.variants(it) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        // Folded a character at a time, so offsets in the folded text are offsets in the sentence.
        val folded = CharArray(sentence.length) { i -> SearchText.fold(sentence[i].toString()).firstOrNull() ?: sentence[i] }.concatToString()
        return WORD.findAll(folded).filter { m -> wanted.any { it == m.value || (it.length >= 3 && m.value.startsWith(it)) } }
            .map { it.range }.toList()
    }

    /** The Medical banner's words over a medical query's results (Ask's banner, Plan SQ Decision 8). */
    val medical: UiText get() = UiText.Res("research_medical")

    /** "Did you mean <name>?" over the results. */
    fun didYouMean(name: String): UiText = UiText.Res("search_did_you_mean", name)

    /** An album's work id starts so (`MediaIndex`'s ids); an audiobook's is its own id. */
    private const val ALBUM = "album:"
    private val SPACES = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val BOLD = Regex("<b>\\s*[^<\\s]", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ")
}
