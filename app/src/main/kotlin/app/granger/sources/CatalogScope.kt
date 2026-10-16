package app.granger.sources

import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import java.net.URI

/** Where packs can be fetched from; each has its own source screen. */
enum class CatalogScope(val title: String) { GRANGER("Granger"), KIWIX("Kiwix"), MODELS("Models") }

/** Models are Models; a pack with any mirror on kiwix.org is Kiwix's; every other pack is Granger's own. */
fun scopeOf(item: CatalogItem): CatalogScope = when (item) {
    is ModelEntry -> CatalogScope.MODELS
    is PackEntry -> if (item.urls.any(::isKiwixUrl)) CatalogScope.KIWIX else CatalogScope.GRANGER
}

private fun isKiwixUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "kiwix.org" || host.endsWith(".kiwix.org")
}
