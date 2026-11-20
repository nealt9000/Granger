package app.granger.core.library

/** Decides where a pack, book, album or audiobook is filed by default: which collection, named what, under which media type. */
object Filing {
    private class Rule(val matches: (String) -> Boolean, val key: String, val name: String)

    /**
     * Mapping from `Pack.name` to a collection key and name; first match wins. Language-neutral on purpose (Plan L3):
     * `wikipedia_es_all` files beside `wikipedia_en_all` in Wikipedia, as each project is one collection whatever its
     * language; the names are the projects' own.
     */
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
        when (pack.bookSource) {
            BookOrigin.GUTENBERG -> return gutenbergKey(pack.name)
            BookOrigin.NAUTILUS, BookOrigin.KIWIX -> return kiwixBooksKey(pack.name)
            else -> {}
        }
        return RULES.firstOrNull { it.matches(pack.name) }?.key ?: "pack:${pack.name}"
    }

    /** The name of the collection with [key]; [pack] supplies the title for one-off `pack:` collections. */
    fun defaultName(key: String, pack: Pack): String = when {
        key == "other" -> "Other"
        pack.isGutenberg && key == gutenbergKey(pack.name) -> gutenbergName(pack)
        (pack.bookSource == BookOrigin.NAUTILUS || pack.bookSource == BookOrigin.KIWIX) && key == kiwixBooksKey(pack.name) ->
            kiwixBooksName(pack, pack.name)
        key.startsWith("pack:") -> pack.title
        else -> RULES.firstOrNull { it.key == key }?.name ?: pack.title
    }

    /** Kiwix's OpenStreetMap packs are maps; every other ZIM is reading material. */
    fun defaultMediaType(pack: Pack): String = if (pack.name.startsWith("maps_")) "maps" else "literature"

    /** The `defaultKey` of the app-made Kiwix folder (`c:kiwix`, "Kiwix", in Literature). */
    const val FOLDER_KEY = FilingKeys.FOLDER_KEY

    const val FOLDER_NAME = FilingKeys.FOLDER_NAME

    /**
     * Whether [pack] is Kiwix's: every named ZIM except Granger's own. Packs have no origin field, so this covers
     * sideloaded Kiwix ZIMs too. Nameless packs ("Other") are nobody's.
     */
    fun isKiwix(pack: Pack): Boolean = pack.name.isNotBlank() && !pack.name.startsWith("granger_classics")

    /** The folder a new app-made collection for [pack] goes in: [FOLDER_KEY] for Kiwix's reading packs, none for maps. */
    fun folderKey(pack: Pack): String? =
        FOLDER_KEY.takeIf { isKiwix(pack) && !pack.isBookPack && defaultMediaType(pack) == "literature" }

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

    /**
     * The collection of a nautilus pack's documents (Plan KB Decision 7), or a manifest pack's books (Plan KB2),
     * `kiwixbooks-<pack name>`: one per pack, at the top of Literature beside the Gutenberg collections, not in the Kiwix
     * folder.
     */
    fun kiwixBooksKey(packName: String): String = "$KIWIX_BOOKS_PREFIX$packName"

    /** Whether the collection with [defaultKey] holds a nautilus or manifest pack's books. */
    fun isKiwixBooksCollection(defaultKey: String?): Boolean = defaultKey?.startsWith(KIWIX_BOOKS_PREFIX) == true

    /** Whether the collection with [defaultKey] holds a book pack's books (Gutenberg or nautilus). */
    fun isBookPackCollection(defaultKey: String?): Boolean =
        isGutenbergCollection(defaultKey) || isKiwixBooksCollection(defaultKey)

    /**
     * A nautilus or manifest collection's name: its manifest's title (Plan KB2), else its pack's title ("Food for
     * Preppers"), else the pack's ZIM name.
     */
    fun kiwixBooksName(pack: Pack?, packName: String): String =
        pack?.let(BookManifest::titleOf) ?: pack?.title?.takeIf { it.isNotBlank() } ?: packName

    private const val KIWIX_BOOKS_PREFIX = "kiwixbooks-"

    /** Granger Classics' collection key: Granger's English books. */
    const val GRANGER_CLASSICS = "granger-classics"

    /** Clásicos Granger's collection key: Granger's Spanish books (Plan L3), a collection of their own. */
    const val CLASICOS_GRANGER = "clasicos-granger"

    /** Clásicos Granger's own name, the same in every language, as its catalog preset names it. */
    const val CLASICOS_GRANGER_NAME = "Clásicos Granger"

    /** The catalog id prefix of Clásicos Granger's books (the builder's `bookIdPrefix`, `gc-es-…`). */
    const val CLASICOS_ID_PREFIX = "gc-es-"

    /** Granger's book collections by knowledge language (Plan L3); the catalog's `collection` of each one's books. */
    val GRANGER_COLLECTIONS = mapOf("en" to GRANGER_CLASSICS, "es" to CLASICOS_GRANGER)

    /** The Granger collection of knowledge language [language]; Granger Classics for a language without one. */
    fun grangerCollection(language: String): String = GRANGER_COLLECTIONS[language] ?: GRANGER_CLASSICS

    /**
     * The collection a Granger book files in: its catalog collection, which its id says (Plan L3): `gc-es-…` books are
     * Clásicos Granger's, every other one Granger Classics'. The library keeps no catalog collection per book, so the
     * id stands in for it.
     */
    fun grangerKey(sourceRef: String): String = when {
        sourceRef.startsWith(CLASICOS_ID_PREFIX) -> CLASICOS_GRANGER
        else -> bookSetKey(sourceRef) ?: GRANGER_CLASSICS
    }

    /**
     * A catalog book set Granger publishes that isn't one of its own collections (Plan KB2 Task 3.2): the id prefix of
     * its books (`asb-<lang>-<node>`), its collections' key prefix (`african-storybook-<lang>`, the catalog's set id and
     * books' `collection`), the project's name and who made the books.
     */
    private class BookSetRule(val idPrefix: String, val keyPrefix: String, val name: String, val provider: String)

    private val BOOK_SETS = listOf(BookSetRule("asb-", "african-storybook-", "African Storybook", "African Storybook Initiative"))

    /** The language names a set's collection is called by, in the language itself ("Kiswahili"); else the code. */
    private val SET_LANGUAGES = mapOf("en" to "English", "sw" to "Kiswahili", "es" to "Español", "fr" to "Français", "pt" to "Português")

    /**
     * The collection a set book files in (Plan KB2 Task 3.2): `african-storybook-en` for `asb-en-…`, one per language,
     * not Granger Classics; null for a book of no set.
     */
    fun bookSetKey(sourceRef: String): String? = BOOK_SETS.firstOrNull { sourceRef.startsWith(it.idPrefix) }?.let { rule ->
        sourceRef.removePrefix(rule.idPrefix).substringBefore('-', "").takeIf { it.isNotEmpty() }?.let { rule.keyPrefix + it }
    }

    /** Whether the collection with [defaultKey] holds a catalog book set's books (African Storybook · English). */
    fun isBookSetCollection(defaultKey: String?): Boolean = defaultKey != null && BOOK_SETS.any { defaultKey.startsWith(it.keyPrefix) }

    /** A set collection's name: "African Storybook · English", "African Storybook · Kiswahili". */
    fun bookSetName(defaultKey: String): String? = BOOK_SETS.firstOrNull { defaultKey.startsWith(it.keyPrefix) }?.let { rule ->
        val lang = defaultKey.removePrefix(rule.keyPrefix)
        "${rule.name} · ${SET_LANGUAGES[lang] ?: lang}"
    }

    /** Who made a set collection's books ("African Storybook Initiative"), which its header says; null for any other. */
    fun bookSetProvider(defaultKey: String?): String? =
        defaultKey?.let { key -> BOOK_SETS.firstOrNull { key.startsWith(it.keyPrefix) }?.provider }

    /** Books are filed by origin; these are their collections' keys and names. */
    private val BOOK_COLLECTIONS = mapOf(
        BookOrigin.GRANGER to (GRANGER_CLASSICS to "Granger Classics"),
        BookOrigin.IMPORT to ("my-books" to "My books"),
        BookOrigin.ARCHIVE_ORG to ("archive-org" to "Archive.org"),
    )

    /** Identity of a book across re-downloads: its id, which is derived from origin and source. */
    fun bookKey(book: Book): String = book.id

    /**
     * A pack book goes in its pack's collection ([gutenbergKey], [kiwixBooksKey]); a part of an archive.org set (its
     * ref `<identifier>~03`, Plan IA) in its set's own collection, `archive-org:<identifier>`, so the parts sit together
     * and in order; every other book by its origin.
     */
    fun defaultKey(book: Book): String = when {
        book.origin == BookOrigin.GUTENBERG -> gutenbergKey(PackBooks.packNameOf(book))
        book.origin == BookOrigin.NAUTILUS || book.origin == BookOrigin.KIWIX -> kiwixBooksKey(PackBooks.packNameOf(book))
        book.origin == BookOrigin.ARCHIVE_ORG && SET_PART in book.sourceRef ->
            BOOK_COLLECTIONS.getValue(BookOrigin.ARCHIVE_ORG).first + ":" + book.sourceRef.substringBefore(SET_PART)
        book.origin == BookOrigin.GRANGER -> grangerKey(book.sourceRef)
        else -> BOOK_COLLECTIONS.getValue(book.origin).first
    }

    /**
     * [packs] name a pack book's collection: a Gutenberg pack's subject, a nautilus pack's title. A set's collection is
     * named for its item: the part's title before " · " ("Batman (1986) · 02 (of 04)").
     */
    fun defaultName(key: String, book: Book, packs: List<Pack> = emptyList()): String = when {
        book.origin == BookOrigin.GUTENBERG -> gutenbergName(packs.firstOrNull { it.name == PackBooks.packNameOf(book) })
        book.origin == BookOrigin.NAUTILUS || book.origin == BookOrigin.KIWIX ->
            PackBooks.packNameOf(book).let { name -> kiwixBooksName(packs.firstOrNull { it.name == name }, name) }
        key == CLASICOS_GRANGER -> CLASICOS_GRANGER_NAME
        isBookSetCollection(key) -> bookSetName(key) ?: book.title
        else -> BOOK_COLLECTIONS.values.firstOrNull { it.first == key }?.second
            ?: book.title.substringBeforeLast(SET_TITLE).takeIf { key.startsWith("archive-org:") && SET_TITLE in book.title }
            ?: book.title
    }

    /**
     * The collections a pack book is in besides any it's placed in (Plan KB2 Task 4.7): one per installed pack listing it
     * ([Book.memberOf]), so a Gutenberg book in a subject pack and in `gutenberg_en_all` is in both. Empty for any other
     * book, or a row from before its packs were read.
     */
    fun memberKeys(book: Book): List<String> {
        val names = book.memberOf?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        return when (book.origin) {
            BookOrigin.GUTENBERG -> names.map(::gutenbergKey)
            BookOrigin.NAUTILUS, BookOrigin.KIWIX -> names.map(::kiwixBooksKey)
            else -> emptyList()
        }
    }

    /** Whether [book] belongs in the book pack collection with [defaultKey]: its own pack's, or another pack's listing it. */
    fun inBookPackCollection(book: Book, defaultKey: String): Boolean =
        defaultKey(book) == defaultKey || defaultKey in memberKeys(book)

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

    /**
     * The English name the app stores for an app-made collection with [defaultKey] (Plan L3): the app shows its own
     * words for it instead, in the app's language, while the stored name is still this one (not renamed). Null for a
     * key whose name is a pack's or a set's.
     */
    fun storedDefaultName(defaultKey: String): String? = when {
        defaultKey == "other" -> "Other"
        defaultKey == FOLDER_KEY -> FOLDER_NAME
        else -> BOOK_COLLECTIONS.values.firstOrNull { it.first == defaultKey }?.second
            ?: defaultKey.substringAfter(':', "").let { origin -> MEDIA_COLLECTIONS.values.firstOrNull { it.first == origin }?.second }
    }

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
