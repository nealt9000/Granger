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
) : CatalogItem

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
) : CatalogItem

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
