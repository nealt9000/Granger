package app.granger.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The signed list of packs and models the app can download. */
@Serializable
data class Catalog(
    val formatVersion: Int,
    val generatedAt: String,
    val packs: List<PackEntry> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    val items: List<CatalogItem> get() = models + packs

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

@Serializable
data class Preset(val id: String, val title: String, val items: List<String>)
