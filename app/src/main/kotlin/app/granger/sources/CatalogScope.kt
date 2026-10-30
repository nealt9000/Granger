package app.granger.sources

import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.VoiceEntry
import app.granger.core.library.GutenbergNames
import java.net.URI

/** Where packs can be fetched from; each has its own source screen. */
enum class CatalogScope(val title: String) {
    GRANGER("Granger"), KIWIX("Kiwix"), MODELS("Models"),
    /** Kiwix's Project Gutenberg packs, offered as books (Plan GB Decision 7). */
    GUTENBERG("Project Gutenberg"),
}

/**
 * Models (and voices) are Models; a Project Gutenberg pack is Gutenberg's (Plan GB: its books are the way in, so it's
 * offered under Books, not among Kiwix's knowledge packs); any other pack with a mirror on kiwix.org is Kiwix's; every
 * other pack is Granger's own.
 */
fun scopeOf(item: CatalogItem): CatalogScope = when (item) {
    is ModelEntry, is VoiceEntry -> CatalogScope.MODELS
    // Books are Granger's own.
    is BookEntry -> CatalogScope.GRANGER
    is PackEntry -> when {
        isGutenberg(item) -> CatalogScope.GUTENBERG
        item.urls.any(::isKiwixUrl) -> CatalogScope.KIWIX
        else -> CatalogScope.GRANGER
    }
}

/** By its catalog family, or failing that its ZIM name (Plan GB Decision 1). */
fun isGutenberg(pack: PackEntry): Boolean = pack.family == "gutenberg" || GutenbergNames.isGutenberg(pack.zimName)

private fun isKiwixUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "kiwix.org" || host.endsWith(".kiwix.org")
}
