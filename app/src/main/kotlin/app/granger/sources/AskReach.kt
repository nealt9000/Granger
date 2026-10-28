package app.granger.sources

import app.granger.core.catalog.PackEntry

/** How Ask can search a pack (Plan U3b Decision 8). */
enum class AskReach(val text: String) {
    MEANING("Ask: by meaning and keywords"),
    KEYWORDS("Ask: keywords only"),
    NONE("Ask can't search this"),
}

/**
 * How Ask searches [entry]: by meaning when it has search vectors (in the catalog, or installed: [installedVectorParents]
 * holds the catalog ids whose VECTORS record is installed), by keyword with a full-text index, else not at all. Null
 * for maps, which say nothing: nobody asks a map.
 */
fun askReach(entry: PackEntry, installedVectorParents: Set<String>): AskReach? = when {
    entry.family == MAPS_FAMILY -> null
    entry.vectors != null || entry.id in installedVectorParents -> AskReach.MEANING
    entry.hasFulltextIndex -> AskReach.KEYWORDS
    else -> AskReach.NONE
}

private const val MAPS_FAMILY = "maps"
