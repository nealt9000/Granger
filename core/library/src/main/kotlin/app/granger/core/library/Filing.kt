package app.granger.core.library

/** Decides where a pack, book, album or audiobook is filed by default: which collection, named what, under which media type. */
object Filing {
    private class Rule(val matches: (String) -> Boolean, val key: String, val name: String)

    /** Mapping from `Pack.name` to a collection key and name; first match wins. */
    private val RULES = listOf(
        Rule({ it.startsWith("wikipedia_") }, "wikipedia", "Wikipedia"),
        Rule({ it.startsWith("wiktionary_") }, "wiktionary", "Wiktionary"),
        Rule({ it.startsWith("wikisource_") }, "wikisource", "Wikisource"),
        Rule({ it.startsWith("wikem_") }, "wikem", "WikEM"),
        Rule({ it.startsWith("granger_classics") }, "granger-classics", "Granger Classics"),
        Rule({ it.startsWith("ifixit_") }, "ifixit", "iFixit"),
        Rule({ it.startsWith("wikivoyage_") }, "wikivoyage", "Wikivoyage"),
        Rule({ it.startsWith("wikibooks_") }, "wikibooks", "Wikibooks"),
        Rule({ it.startsWith("wikiquote_") }, "wikiquote", "Wikiquote"),
        Rule({ it.startsWith("wikiversity_") }, "wikiversity", "Wikiversity"),
        Rule({ it.startsWith("phet_") }, "phet", "PhET"),
        Rule({ it.startsWith("maps_") }, "maps", "OpenStreetMap"),
        Rule({ it.startsWith("devdocs_") }, "devdocs", "DevDocs"),
        Rule({ "stackexchange" in it || "stackoverflow" in it || it.substringBefore('_') in STACK_EXCHANGE_SITES },
            "stack-exchange", "Stack Exchange"),
    )

    /** The Stack Exchange sites whose ZIM names don't say so (Kiwix's catalog, 2026-10-20). */
    private val STACK_EXCHANGE_SITES = setOf("mathoverflow.net", "stackapps.com", "serverfault.com", "superuser.com", "askubuntu.com")

    /** Identity of a pack across updates: its ZIM `Name`, else its id. */
    fun packKey(pack: Pack): String = pack.name.ifBlank { pack.id }

    fun defaultKey(pack: Pack): String {
        if (pack.name.isBlank()) return "other"
        if (pack.isGutenberg) return gutenbergKey(pack.name)
        return RULES.firstOrNull { it.matches(pack.name) }?.key ?: "pack:${pack.name}"
    }

    /** The name of the collection with [key]; [pack] supplies the title for one-off `pack:` collections. */
    fun defaultName(key: String, pack: Pack): String = when {
        key == "other" -> "Other"
        pack.isGutenberg && key == gutenbergKey(pack.name) -> gutenbergName(pack)
        key.startsWith("pack:") -> pack.title
        else -> RULES.firstOrNull { it.key == key }?.name ?: pack.title
    }

    /** Kiwix's OpenStreetMap packs are maps; every other ZIM is reading material. */
    fun defaultMediaType(pack: Pack): String = if (pack.name.startsWith("maps_")) "maps" else "literature"

    /** The `defaultKey` of the app-made Kiwix folder (`c:kiwix`, "Kiwix", in Literature). */
    const val FOLDER_KEY = "kiwix"

    const val FOLDER_NAME = "Kiwix"

    /**
     * Whether [pack] is Kiwix's: every named ZIM except Granger's own. Packs have no origin field, so this covers
     * sideloaded Kiwix ZIMs too. Nameless packs ("Other") are nobody's.
     */
    fun isKiwix(pack: Pack): Boolean = pack.name.isNotBlank() && !pack.name.startsWith("granger_classics")

    /** The folder a new app-made collection for [pack] goes in: [FOLDER_KEY] for Kiwix's reading packs, none for maps. */
    fun folderKey(pack: Pack): String? =
        FOLDER_KEY.takeIf { isKiwix(pack) && !pack.isGutenberg && defaultMediaType(pack) == "literature" }

    /**
     * The collection of a Gutenberg pack's books (Plan GB Decision 4), `gutenberg-<lcc>` from the ZIM name
     * (`gutenberg_en_lcc-m` → `gutenberg-m`; `gutenberg_en_all` → `gutenberg-en_all`). It sits at the top of
     * Literature, beside Granger Classics, not in the Kiwix folder; the pack itself isn't shelved ([RoomShelves]).
     */
    fun gutenbergKey(packName: String): String {
        val rest = packName.removePrefix("gutenberg_")
        return "$GUTENBERG_PREFIX${rest.substringAfter("lcc-", "").ifEmpty { rest }}"
    }

    /** Whether the collection with [defaultKey] holds a Gutenberg pack's books. */
    fun isGutenbergCollection(defaultKey: String?): Boolean = defaultKey?.startsWith(GUTENBERG_PREFIX) == true

    /** A Gutenberg collection's name: the pack's subject alone ("Music and books on music"), else "Project Gutenberg". */
    fun gutenbergName(pack: Pack?): String =
        pack?.let { GutenbergNames.subject(it.name, it.description) }?.ifEmpty { null } ?: GutenbergNames.PROVIDER

    private const val GUTENBERG_PREFIX = "gutenberg-"

    /** Books are filed by origin; these are their collections' keys and names. */
    private val BOOK_COLLECTIONS = mapOf(
        BookOrigin.GRANGER to ("granger-classics" to "Granger Classics"),
        BookOrigin.IMPORT to ("my-books" to "My books"),
        BookOrigin.ARCHIVE_ORG to ("archive-org" to "Archive.org"),
    )

    /** Identity of a book across re-downloads: its id, which is derived from origin and source. */
    fun bookKey(book: Book): String = book.id

    /**
     * A Gutenberg book goes in its pack's collection ([gutenbergKey]); a part of an archive.org set (its ref
     * `<identifier>~03`, Plan IA) in its set's own collection, `archive-org:<identifier>`, so the parts sit together and
     * in order; every other book by its origin.
     */
    fun defaultKey(book: Book): String = when {
        book.origin == BookOrigin.GUTENBERG -> gutenbergKey(GutenbergBooks.packNameOf(book))
        book.origin == BookOrigin.ARCHIVE_ORG && SET_PART in book.sourceRef ->
            BOOK_COLLECTIONS.getValue(BookOrigin.ARCHIVE_ORG).first + ":" + book.sourceRef.substringBefore(SET_PART)
        else -> BOOK_COLLECTIONS.getValue(book.origin).first
    }

    /**
     * [packs] name a Gutenberg book's collection: its pack's subject. A set's collection is named for its item: the
     * part's title before " · " ("Batman (1986) · 02 (of 04)").
     */
    fun defaultName(key: String, book: Book, packs: List<Pack> = emptyList()): String = when {
        book.origin == BookOrigin.GUTENBERG -> gutenbergName(packs.firstOrNull { it.name == GutenbergBooks.packNameOf(book) })
        else -> BOOK_COLLECTIONS.values.firstOrNull { it.first == key }?.second
            ?: book.title.substringBeforeLast(SET_TITLE).takeIf { key.startsWith("archive-org:") && SET_TITLE in book.title }
            ?: book.title
    }

    /** How an archive.org part's ref and title mark it (ArchiveDownloads.PART_SEPARATOR and PART_TITLE_SEPARATOR in the app). */
    private const val SET_PART = '~'
    private const val SET_TITLE = " · "

    /** Every book is reading material. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultMediaType(book: Book): String = "literature"

    /** Identity of an album across re-scans: `album:<albumKey>`. */
    fun albumKey(a: AlbumSummary): String = "album:${a.key}"

    /** Identity of an audiobook across re-scans: its id (`audiobook:<origin>:<ref>`). */
    fun audiobookKey(book: Audiobook): String = book.id

    /** Albums and audiobooks are filed by origin; these are their collections' keys and names. */
    private val MEDIA_COLLECTIONS = mapOf(
        MediaOrigin.PHONE to ("on-this-phone" to "On this phone"),
        MediaOrigin.LIBRIVOX to ("librivox" to "LibriVox"),
        MediaOrigin.ARCHIVE_ORG to ("archive-org" to "Archive.org"),
    )

    fun defaultKey(origin: MediaOrigin): String = MEDIA_COLLECTIONS.getValue(origin).first

    /** The name of the media collection with [key] ("On this phone" for `on-this-phone`). */
    fun defaultName(key: String): String =
        MEDIA_COLLECTIONS.values.firstOrNull { it.first == key }?.second ?: key

    @Suppress("UNUSED_PARAMETER")
    fun defaultMediaType(album: AlbumSummary): String = "music"

    @Suppress("UNUSED_PARAMETER")
    fun defaultMediaType(book: Audiobook): String = "audiobooks"

    /**
     * The stored `defaultKey` of a media collection. The same origin key ("on-this-phone") names a collection in
     * both Music and Audiobooks, and stored default keys are unique, so the stored one is scoped by media type
     * (`music:on-this-phone`, `audiobooks:on-this-phone`). No pack or book default key contains `:` except `pack:`.
     */
    fun mediaCollectionKey(mediaTypeId: String, key: String): String = "$mediaTypeId:$key"

    /** The old Classics ZIM, replaced by the Granger Classics books. Only reported; nothing is hidden or deleted here. */
    fun isSuperseded(pack: Pack): Boolean = pack.name == "granger_classics_en"
}
