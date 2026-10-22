package app.granger.sources

import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.VoiceEntry
import java.net.URI

/** Where packs can be fetched from; each has its own source screen. */
enum class CatalogScope(val title: String) { GRANGER("Granger"), KIWIX("Kiwix"), MODELS("Models") }

/** Models (and voices) are Models; a pack with any mirror on kiwix.org is Kiwix's; every other pack is Granger's own. */
fun scopeOf(item: CatalogItem): CatalogScope = when (item) {
    is ModelEntry, is VoiceEntry -> CatalogScope.MODELS
    // Books are Granger's own.
    is BookEntry -> CatalogScope.GRANGER
    is PackEntry -> if (item.urls.any(::isKiwixUrl)) CatalogScope.KIWIX else CatalogScope.GRANGER
}

private fun isKiwixUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "kiwix.org" || host.endsWith(".kiwix.org")
}
