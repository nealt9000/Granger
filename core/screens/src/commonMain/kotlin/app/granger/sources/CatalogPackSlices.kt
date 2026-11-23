package app.granger.sources

import app.granger.core.catalog.Catalog
import app.granger.core.library.Pack
import app.granger.core.library.PackSlices
import app.granger.core.library.SliceKey
import kotlin.concurrent.Volatile

/**
 * Each installed pack's set and rank (Plan WF Decision 6), from the current catalog by the pack's ZIM name: Search's and
 * Ask's slice de-duplication ([app.granger.core.library.SliceDedupe]). Shared with the iPhone since Plan I7 Task 7b
 * (moved from Android's `SetTidy.kt`).
 */
class CatalogPackSlices(private val catalog: () -> Catalog?) : PackSlices {
    @Volatile private var cached: Pair<Catalog, Map<String, SliceKey>>? = null

    override fun of(pack: Pack): SliceKey? {
        val current = catalog() ?: return null
        val table = cached?.takeIf { it.first === current }?.second
            ?: current.packs.filter { it.isSlice }
                .associate { it.zimName to SliceKey(it.setId, it.setRank) }
                .also { cached = current to it }
        return table[pack.name]
    }
}
