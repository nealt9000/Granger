package app.granger.core.catalog

import kotlinx.coroutines.flow.StateFlow

// Plan I4b Task 6: shared, so Get more's model (core:screens) reads the catalog on both phones. Android's
// CatalogRepository (core:catalog) implements it.

data class CatalogState(
    /** The catalog to show, already filtered for this build; null until one has been verified. */
    val catalog: Catalog? = null,
    /** When [catalog] was downloaded (epoch ms). */
    val fetchedAt: Long? = null,
    val refreshing: Boolean = false,
    /** Why the last refresh kept the old catalog; null after a successful refresh. */
    val error: CatalogError? = null,
)

interface CatalogSource {
    val state: StateFlow<CatalogState>

    /** Shows the last good catalog from the cache. No network. */
    suspend fun load()

    /** Downloads, verifies and caches the catalog. On any failure the shown catalog stays and [CatalogState.error] says why. */
    suspend fun refresh()
}
