package app.granger.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** The signed list of packs, models and books the app can download. */
@Serializable
data class Catalog(
    val formatVersion: Int,
    val generatedAt: String,
    val packs: List<PackEntry> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val books: List<BookEntry> = emptyList(),
    val presets: List<Preset> = emptyList(),
    /** Downloadable voices for read-aloud (Plan V1); absent in older catalogs. */
    val voices: List<VoiceEntry> = emptyList(),
    /** The readiness model (Plan H1, spec §3); absent in older catalogs. */
    val readiness: Readiness? = null,
    /** Onboarding's use cases and larger map areas (Plan H3); null in older catalogs. */
    val onboarding: Onboarding? = null,
    /**
     * Sets of books that aren't Granger Classics (Plan KB2 Task 3.2: African Storybook, one per language), each with its
     * own books and presets. A top-level array older apps skip: they file every [books] entry into Granger Classics.
     */
    val bookSets: List<BookSet> = emptyList(),
) {
    /** Every set's books (Plan KB2): catalog items like [books], but not Granger's own collections. */
    val setBooks: List<BookEntry> get() = bookSets.flatMap { it.books }

    val items: List<CatalogItem> get() = models + packs + books + setBooks + voices

    /** Items by id, the first of each id winning, built once: with the sets, lists hold thousands of entries. */
    private val byId: Map<String, CatalogItem> by lazy {
        HashMap<String, CatalogItem>().apply { items.forEach { if (it.id !in this) put(it.id, it) } }
    }

    fun item(id: String): CatalogItem? = byId[id]

    /** The set with [id]; null when the catalog has none. */
    fun bookSet(id: String): BookSet? = bookSets.firstOrNull { it.id == id }

    /**
     * Release builds list only models that passed the eval (spec §3 "Catalog"). Without [includeVectors] (a phone too
     * small for Ask, Plan E1) packs and books carry no search vectors, so their sizes, the review and the downloads
     * leave out files only Ask reads.
     */
    fun forBuild(includeUnevaluatedModels: Boolean, includeVectors: Boolean = true): Catalog {
        val evaluated = if (includeUnevaluatedModels) this else copy(models = models.filter { it.evalPassed })
        return if (includeVectors) evaluated
        else evaluated.copy(packs = packs.map { it.copy(vectors = null) }, books = books.map { it.copy(vectors = null) })
    }

    /**
     * The knowledge languages the readiness model has tiers for (Plan L3): English first when its lists name anything,
     * then each other language in the order the domains name them. Empty without a readiness model.
     */
    val languages: List<String>
        get() {
            val domains = readiness?.domains.orEmpty()
            val out = LinkedHashSet<String>()
            if (domains.any { it.comprehensive.isNotEmpty() }) out += ENGLISH
            domains.forEach { d -> d.languages.forEach { (code, tiers) -> if (tiers.comprehensive.isNotEmpty()) out += code } }
            return out.toList()
        }

    /**
     * The start choices' presets for knowledge language [lang] (Plan L3), Efficient then Comprehensive: `efficient` and
     * `comprehensive` for English, `efficient-<lang>` and `comprehensive-<lang>` otherwise. A missing one is left out.
     */
    fun tierPresets(lang: String): List<Preset> {
        val code = Languages.normalise(lang)
        return TIER_PRESETS.mapNotNull { id ->
            val wanted = if (code == ENGLISH) id else "$id-$code"
            presets.firstOrNull { it.id == wanted && it.language == code }
        }
    }

    companion object {
        /** The generated tier presets' English ids (Plan H1); another language's carry "-<code>". */
        val TIER_PRESETS = listOf("efficient", "comprehensive")
    }
}

/** English, the catalog's own language: its lists and copy are the plain fields (Plan L3). */
const val ENGLISH = "en"

/**
 * Catalog copy in another language (Plan L3): the fields a domain, preset, use case, set or curated pack writes, blank
 * where that language has none. The app shows the app language's copy, else the catalog's own ([localised]).
 */
@Serializable
data class Copy(
    val title: String = "",
    val blurb: String = "",
    /** A set's slice: its label, and the set's name and line (Plan WF's sets, in this language). */
    val setLabel: String = "",
    val setTitle: String = "",
    val setDescription: String = "",
)

/**
 * The field [pick] of [i18n]'s copy for app language [appLang] when it has one, else [own], the catalog's own text.
 * That is English for generator copy; a Spanish pack's own title is Spanish, with its English in `i18n.en`.
 */
fun localised(i18n: Map<String, Copy>, appLang: String, own: String, pick: (Copy) -> String = Copy::title): String =
    i18n[Languages.normalise(appLang)]?.let(pick)?.ifBlank { null } ?: own

/** A readiness domain's lists in one language (Plan L3). */
@Serializable
data class Tiers(val essential: List<String> = emptyList(), val comprehensive: List<String> = emptyList())

/** A use case's items in one language (Plan L3). */
@Serializable
data class Items(val items: List<String> = emptyList())

/** Anything the downloader can fetch and verify. */
sealed interface CatalogItem {
    val id: String
    val title: String
    val sizeBytes: Long
    val sha256: String
    val urls: List<String>
    val license: String
}

/** [PackEntry.content] of a pack that is a bundle of books (Plan KB). */
const val CONTENT_BOOKS = "books"

/** [PackEntry.shelf]'s values (Plan KB2 Task 1.4), in the order Get more → Books shows them. */
object Shelves {
    const val LITERATURE = "literature"
    const val CHILDREN = "children"
    const val MANUALS = "manuals"
    const val LEARNING = "learning"
    val ORDER = listOf(LITERATURE, CHILDREN, MANUALS, LEARNING)
}

@Serializable
data class PackEntry(
    override val id: String,
    override val title: String,
    val description: String = "",
    val category: String,
    val language: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val urls: List<String>,
    val snapshotDate: String,
    override val license: String,
    val hasFulltextIndex: Boolean,
    /** Reserved for v2 curation. */
    val topics: List<String> = emptyList(),
    /** The ZIM's UUID; equals the installed pack's id once downloaded. */
    val zimUuid: String,
    /** The ZIM's `Name` metadata, shared by every snapshot (and flavour) of the same content. */
    val zimName: String,
    /** The license forbids commercial use (iFixit): the app says Kiwix provides the pack. Absent in older catalogs. */
    val nonCommercial: Boolean = false,
    /** One of the hand-picked packs, listed under Recommended. Absent in older catalogs. */
    val featured: Boolean = false,
    /** The pack's family in the generator's family table (e.g. "wikipedia"); blank in older catalogs. */
    val family: String = "",
    /** One of the readiness domain ids (spec §3.1), or blank: the pack counts in storage only. Absent in older catalogs. */
    val domain: String = "",
    /**
     * What the pack holds, when it isn't a website (Plan KB Decision 2): [CONTENT_BOOKS] for a bundle of books that
     * lists its own (gutenberg2zim, nautiluszim), else blank. Absent in older catalogs.
     */
    val content: String = "",
    /** The ZIM's flavour, "maxi", "nopic" or "mini", or blank for an unflavoured ZIM. Absent in older catalogs. */
    val flavour: String = "",
    /** The pack's search vectors (Plan R1), downloaded with it; null when it has none, and in older catalogs. */
    val vectors: VectorsEntry? = null,
    /** The pack's time index (Plan H5b), downloaded with it on every phone; null when it has none, and in older catalogs. */
    val timeIndex: TimeIndexEntry? = null,
    /** The pack's other sidecars (Linked Library spec §5.1), downloaded with it; empty in older catalogs. */
    val sidecars: List<SidecarEntry> = emptyList(),
    /** Topic records (Plan O6), read online by range for full topic pages; never downloaded. Null without, and in older catalogs. */
    val records: RecordsEntry? = null,
    /**
     * The set this pack is a slice of (Plan WF Decision 2): "wikipedia_en", "wikivoyage_en"; blank for any other pack,
     * and in older catalogs. A slice of higher [setRank] holds the smaller ones' articles.
     */
    @SerialName("set") val setId: String = "",
    /** The slice's place in its set, 1 the smallest; 0 when [setId] is blank. */
    val setRank: Int = 0,
    /** How much the slice is, as its choice says it: "Best 50,000 articles", "Everything", "Europe". */
    val setLabel: String = "",
    /** The set this pack is a subject of (WikiMed, Chemistry: "wikipedia_en"); blank for any other pack. */
    val subjectOf: String = "",
    /** A subject's share of articles not in each slice of its set, by the slice's ZIM name (Plan WF Decision 1). */
    val adds: Map<String, Double> = emptyMap(),
    /** The pack's article count, measured (Plan WF Task 1); 0 when unknown. */
    val articles: Long = 0,
    /** A test pack (the 2 MB Ray Charles sample): kept in the catalog, left off the lists. */
    val sample: Boolean = false,
    /** A slice's set name and line (Plan L3), blank in older catalogs. */
    val setTitle: String = "",
    val setDescription: String = "",
    /** The curated title and the set's copy in other languages, by language code (Plan L3); empty in older catalogs. */
    val i18n: Map<String, Copy> = emptyMap(),
    /**
     * A book pack's shelf by kind (Plan KB2 Task 1.4), one of [Shelves]: Get more → Books groups by it. Blank for a
     * website, and in older catalogs.
     */
    val shelf: String = "",
    /**
     * Who made a book pack ("Federation of American Scientists", "Project Gutenberg", "Prunelle"), stamped from
     * `book_packs.json` (Plan KB2): the Books screen and the Library say "From Kiwix · <provider>". Blank for a website,
     * and in older catalogs.
     */
    val provider: String = "",
    /**
     * Explore's subject ids (Plan KB2 Task 4.8; ids v1 in tools/subjects/subjects.json), one to three, main first:
     * `["health"]`. Empty for an untagged entry and in older catalogs. See [bookCollectionsBySubject].
     */
    val subjects: List<String> = emptyList(),
) : CatalogItem {
    /** The title in app language [appLang], else the catalog's own (Kiwix's, or the curated one). */
    fun title(appLang: String): String = localised(i18n, appLang, title)

    /**
     * A bundle of books (Gutenberg, nautilus): listed under Books and opened as a collection (Plan KB). A pack with a
     * book manifest (Plan KB2) is one too, though its [content] stays blank so older apps keep it a website.
     */
    val isBookPack: Boolean get() = content == CONTENT_BOOKS || sidecars.any { it.kind == SidecarEntry.GBOOKS }

    /** A slice of a set (Plan WF). */
    val isSlice: Boolean get() = setId.isNotBlank() && setRank > 0

    /** A subject pack of a set (Plan WF). */
    val isSubject: Boolean get() = subjectOf.isNotBlank()
}

/** An e-book (EPUB) the reader opens. */
@Serializable
data class BookEntry(
    override val id: String,
    override val title: String,
    val author: String = "",
    val description: String = "",
    val category: String,
    val language: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val urls: List<String>,
    val releaseDate: String,
    override val license: String,
    val collection: String,
    /** The year the work was first published, when known. */
    val published: Int? = null,
    /** The readiness domain the book counts toward (`literature` or `thought` for Classics); blank in older catalogs. */
    val domain: String = "",
    /** The book's search vectors (Plan R1), downloaded with it; null when it has none, and in older catalogs. */
    val vectors: VectorsEntry? = null,
    /** The book's other sidecars (Linked Library spec §5.1), downloaded with it; empty in older catalogs. */
    val sidecars: List<SidecarEntry> = emptyList(),
    /** A set book's reading level (Plan KB2: African Storybook's 1–5); 0 when it has none. */
    val level: Int = 0,
    /**
     * Explore's subject ids (Plan KB2 Task 4.8; ids v1 in tools/subjects/subjects.json), one to three, main first:
     * `["health"]`. Empty for an untagged entry and in older catalogs. See [bookCollectionsBySubject].
     */
    val subjects: List<String> = emptyList(),
    /**
     * The [BookSet] this book is in (Plan KB2 Task 3.2), set by the parser from where the book was listed, never read
     * from the catalog; blank for a book of the top-level [Catalog.books] (Granger Classics, Clásicos Granger).
     */
    @Transient val bookSet: String = "",
) : CatalogItem {
    /** A set's book (African Storybook), not one of Granger's own collections. */
    val inSet: Boolean get() = bookSet.isNotEmpty()
}

/**
 * A set of books in one language from one provider (Plan KB2 Task 3.2): African Storybook · English. [id] is its
 * books' `collection`; [presets] are its own (every book, then one per level), never in [Catalog.presets], which older
 * apps read. [shelf] is one of [Shelves]. Every field but the id has a default so a sparse set decodes; the parser
 * drops a set with no valid books.
 */
@Serializable
data class BookSet(
    val id: String,
    val title: String = "",
    val description: String = "",
    val language: String = ENGLISH,
    val shelf: String = "",
    /** Who made the books ("African Storybook Initiative"): the Books screen and the collection say it. */
    val provider: String = "",
    val license: String = "",
    val i18n: Map<String, Copy> = emptyMap(),
    val presets: List<Preset> = emptyList(),
    val books: List<BookEntry> = emptyList(),
    /**
     * Explore's subject ids (Plan KB2 Task 4.8; ids v1 in tools/subjects/subjects.json), one to three, main first:
     * `["health"]`. Empty for an untagged entry and in older catalogs. See [bookCollectionsBySubject].
     */
    val subjects: List<String> = emptyList(),
) {
    /** The title in app language [appLang], else the catalog's own. */
    fun title(appLang: String): String = localised(i18n, appLang, title)

    /** Its books' reading levels, lowest first; empty when they have none. */
    val levels: List<Int> get() = books.map { it.level }.filter { it > 0 }.distinct().sorted()
}

/**
 * A pack's or book's precomputed search vectors (Plan R1, spec §4): one `.gvec` file, valid only for that exact pack or
 * book file and for the embedder whose sha256 is [embedderSha256]. Every field has a default so a malformed object
 * decodes; the parser drops one that doesn't validate, keeping its entry.
 */
@Serializable
data class VectorsEntry(
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val embedderSha256: String = "",
    val dims: Int = 0,
    val dtype: String = "",
    val chunkerVersion: Int = 0,
)

/**
 * A pack's time index (Plan H5b): one `.gtime` SQLite file of dated articles, valid for every flavour of that ZIM
 * snapshot (it's keyed by article path). [format] is the file layout the app must read; [lanesVersion] the lanes it was
 * built with. Every field has a default so a malformed object decodes; the parser drops one that doesn't validate.
 */
@Serializable
data class TimeIndexEntry(
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val count: Int = 0,
    val format: Int = 0,
    val lanesVersion: Int = 0,
)

/**
 * One file built offline for a pack or book and downloaded with it (Linked Library spec §5.1): a read-only SQLite file
 * whose `meta` table names its [kind], [schemaVersion] and the exact source file it was built from. [KNOWN] holds the
 * kinds this app reads and the newest schema of each; the parser drops the rest, leaving them for a newer app. Search
 * vectors and the time index stay in their own fields until the catalog moves them here (after Plan SQ). Every field has
 * a default so a malformed object decodes; the parser drops one that doesn't validate.
 */
/**
 * A pack's topic records (Plan O6; tools/topicrecords): the `.grec` read by HTTP range, block by block, and its small
 * directory, fetched once and checked against [dirSha256]. They add nothing to a pack's size: nothing downloads them whole.
 */
@Serializable
data class RecordsEntry(
    val url: String = "",
    val dirUrl: String = "",
    val dirSha256: String = "",
    val dirSizeBytes: Long = 0,
    val sizeBytes: Long = 0,
    val version: Int = 0,
)

@Serializable
data class SidecarEntry(
    val kind: String = "",
    val url: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0,
    val schemaVersion: Int = 0,
) {
    companion object {
        /**
         * Kind -> the newest schema this app reads: the QID index (Plan LL0), the Classics' reference links (Plan LL1) and
         * a Kiwix pack's book manifest (Plan KB2).
         */
        val KNOWN: Map<String, Int> = mapOf("gqid" to 1, "glink" to 1, GBOOKS to 1)

        /** A Kiwix pack's book manifest (Plan KB2, `tools/bookpacks/schema.md`): it makes the pack a book collection. */
        const val GBOOKS = "gbooks"
    }
}

/** The item's sidecars (Linked Library spec §5.1), for the kinds that can have them. */
val CatalogItem.sidecars: List<SidecarEntry>
    get() = when (this) {
        is PackEntry -> sidecars
        is BookEntry -> sidecars
        else -> emptyList()
    }

/** The item's search vectors, for the kinds that can have them. */
val CatalogItem.vectors: VectorsEntry?
    get() = when (this) {
        is PackEntry -> vectors
        is BookEntry -> vectors
        else -> null
    }

/** The pack's time index (Plan H5b); null for every other kind. */
val CatalogItem.timeIndex: TimeIndexEntry?
    get() = (this as? PackEntry)?.timeIndex

/** What getting the item downloads: its file, its vectors, its time index and its sidecars (spec §4, "their size is shown in the item's size"). */
val CatalogItem.downloadBytes: Long
    get() = sizeBytes + (vectors?.sizeBytes ?: 0L) + (timeIndex?.sizeBytes ?: 0L) + sidecars.sumOf { it.sizeBytes }

@Serializable
enum class ModelRole {
    @SerialName("small") SMALL,
    @SerialName("large") LARGE,
    @SerialName("embedder") EMBEDDER,
}

@Serializable
data class ModelEntry(
    override val id: String,
    override val title: String,
    val role: ModelRole,
    val fileName: String,
    override val sizeBytes: Long,
    val minRamGb: Int,
    override val sha256: String,
    val url: String,
    override val license: String,
    val evalPassed: Boolean,
) : CatalogItem {
    override val urls: List<String> get() = listOf(url)
}

/**
 * A voice for read-aloud: one zip (model, `tokens.txt`, `espeak-ng-data/`, `manifest.json`) unpacked into
 * `voices/<id>/` after verification. [id] is the voice directory's name, as sherpa-onnx names it.
 */
@Serializable
data class VoiceEntry(
    override val id: String,
    override val title: String,
    /** `piper` (sherpa-onnx VITS); others may follow. */
    val engine: String,
    val language: String,
    val speakers: Int = 1,
    val sampleRate: Int = 22_050,
    override val sizeBytes: Long,
    /** Bytes on disk once unpacked (the zip itself is deleted). */
    val unpackedBytes: Long = 0,
    override val sha256: String,
    val url: String,
    override val license: String,
    /** The dataset's attribution line, shown in Settings and About. */
    val attribution: String = "",
) : CatalogItem {
    override val urls: List<String> get() = listOf(url)
}

@Serializable
data class Preset(
    val id: String,
    val title: String,
    val items: List<String>,
    /** The knowledge language of a tier or collection preset (Plan L3); English in older catalogs. */
    val language: String = ENGLISH,
    val i18n: Map<String, Copy> = emptyMap(),
) {
    /** The title in app language [appLang], else English. */
    fun title(appLang: String): String = localised(i18n, appLang, title)
}

/** The readiness model (Plan H1, spec §3): the knowledge domains in screen order, each with its tier items. */
@Serializable
data class Readiness(
    val domains: List<Domain> = emptyList(),
    /** ZIM name → the ZIM names whose articles it holds, `prefix*` allowed (Plan H4, Decision 5). */
    val contains: Map<String, List<String>> = emptyMap(),
) {
    /** The ZIM names that hold [zimName]'s articles; never [zimName] itself. */
    fun containers(zimName: String): Set<String> = contains.filterTo(LinkedHashMap()) { (key, held) ->
        key != zimName && held.any { it == zimName || (it.endsWith("*") && zimName.startsWith(it.dropLast(1))) }
    }.keys
}

/**
 * One knowledge domain. A tier item is a catalog id, `@<collection>` (the collection's books whose own [BookEntry.domain]
 * is this domain) or `family:<name>` (any pack of that family). The parser drops tokens the catalog can't resolve.
 */
@Serializable
data class Domain(
    val id: String,
    val title: String,
    /** Installed → Basic. */
    val essential: List<String> = emptyList(),
    /** All installed → Comprehensive. Includes [essential]. */
    val comprehensive: List<String> = emptyList(),
    /** The title in other languages (Plan L3); empty in older catalogs. */
    val i18n: Map<String, Copy> = emptyMap(),
    /** The lists in other languages, by code (Plan L3); English stays in [essential] and [comprehensive]. */
    val languages: Map<String, Tiers> = emptyMap(),
) {
    /** The essential tier in knowledge language [lang]; empty for a language with none. */
    fun essential(lang: String): List<String> =
        Languages.normalise(lang).let { if (it == ENGLISH) essential else languages[it]?.essential.orEmpty() }

    /** The comprehensive tier in knowledge language [lang]; empty for a language with none. */
    fun comprehensive(lang: String): List<String> =
        Languages.normalise(lang).let { if (it == ENGLISH) comprehensive else languages[it]?.comprehensive.orEmpty() }

    /** The title in app language [appLang], else English. */
    fun title(appLang: String): String = localised(i18n, appLang, title)
}

/** Onboarding's tables (Plan H3, spec §6). */
@Serializable
data class Onboarding(
    val useCases: List<UseCase> = emptyList(),
    /** The maps packs that are continents or multi-country areas; the region picker lists the other maps as countries. */
    val larger: List<String> = emptyList(),
    /** Per larger maps pack, the places inside it that have no pack of their own (Puerto Rico → Central America). */
    val covered: Map<String, List<String>> = emptyMap(),
)

/** A use-case chip: what it adds, as readiness-style tokens the parser has resolved. */
@Serializable
data class UseCase(
    val id: String,
    val title: String,
    val blurb: String = "",
    val items: List<String> = emptyList(),
    /** The title and blurb in other languages (Plan L3); empty in older catalogs. */
    val i18n: Map<String, Copy> = emptyMap(),
    /** The items in other languages, by code (Plan L3); English stays in [items]. */
    val languages: Map<String, Items> = emptyMap(),
) {
    /** The items in knowledge language [lang]; empty when the use case isn't offered in it. */
    fun items(lang: String): List<String> =
        Languages.normalise(lang).let { if (it == ENGLISH) items else languages[it]?.items.orEmpty() }

    fun title(appLang: String): String = localised(i18n, appLang, title)

    fun blurb(appLang: String): String = localised(i18n, appLang, blurb, Copy::blurb)
}

/** A subject under this share new to a slice of its set is inside it (Plan WF Decision 5). */
const val COVERED_SHARE = 0.05

/**
 * Which ZIMs hold which (Plan H4 Decision 5, Plan WF Decision 5): the readiness `contains` table; every bigger slice of
 * a set holds the smaller; a subject is held by its set's top slice (the whole of it) and by any slice it adds under
 * [COVERED_SHARE] to. Built once per catalog; [containers] is memoised.
 */
class Containment(private val catalog: Catalog?) {
    private val byZim = catalog?.packs.orEmpty().filter { it.zimName.isNotBlank() }.groupBy { it.zimName }
    private val slicesBySet = catalog?.packs.orEmpty().filter { it.isSlice }.groupBy { it.setId }
    private val memo = HashMap<String, Set<String>>()

    /** The ZIM names that hold [zimName]'s articles; never [zimName] itself. */
    fun containers(zimName: String): Set<String> = memo.getOrPut(zimName) {
        val out = LinkedHashSet<String>()
        catalog?.readiness?.containers(zimName)?.let(out::addAll)
        val entry = byZim[zimName]?.firstOrNull()
        if (entry != null && entry.isSlice) {
            slicesBySet[entry.setId].orEmpty().filter { it.setRank > entry.setRank }.mapTo(out) { it.zimName }
        }
        if (entry != null && entry.isSubject) {
            val slices = slicesBySet[entry.subjectOf].orEmpty()
            val top = slices.maxOfOrNull { it.setRank }
            slices.filter { it.setRank == top || (entry.adds[it.zimName] ?: 1.0) < COVERED_SHARE }.mapTo(out) { it.zimName }
        }
        out.remove(zimName)
        out
    }
}
