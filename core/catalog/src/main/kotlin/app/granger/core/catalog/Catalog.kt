package app.granger.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) {
    val items: List<CatalogItem> get() = models + packs + books + voices

    fun item(id: String): CatalogItem? = items.firstOrNull { it.id == id }

    /** Release builds list only models that passed the eval (spec §3 "Catalog"). */
    fun forBuild(includeUnevaluatedModels: Boolean): Catalog =
        if (includeUnevaluatedModels) this else copy(models = models.filter { it.evalPassed })
}

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
) : CatalogItem {
    /** A bundle of books (Gutenberg, nautilus): listed under Books and opened as a collection (Plan KB). */
    val isBookPack: Boolean get() = content == CONTENT_BOOKS

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
) : CatalogItem

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

/** The item's search vectors, for the kinds that can have them. */
val CatalogItem.vectors: VectorsEntry?
    get() = when (this) {
        is PackEntry -> vectors
        is BookEntry -> vectors
        else -> null
    }

/** What getting the item downloads: its file and its vectors (spec §4, "their size is shown in the item's size"). */
val CatalogItem.downloadBytes: Long get() = sizeBytes + (vectors?.sizeBytes ?: 0L)

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
data class Preset(val id: String, val title: String, val items: List<String>)

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
)

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
data class UseCase(val id: String, val title: String, val blurb: String = "", val items: List<String> = emptyList())
