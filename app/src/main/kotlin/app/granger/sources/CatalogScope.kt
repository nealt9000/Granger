package app.granger.sources

import androidx.annotation.StringRes
import app.granger.R
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.VoiceEntry
import app.granger.core.library.GutenbergNames
import java.net.URI

/** Where packs can be fetched from; each has its own source screen. */
enum class CatalogScope(@StringRes val title: Int) {
    GRANGER(R.string.sources_scope_granger), KIWIX(R.string.sources_scope_kiwix), MODELS(R.string.sources_scope_models),
    /** Kiwix's Project Gutenberg packs, offered as books (Plan GB Decision 7). */
    GUTENBERG(R.string.sources_scope_gutenberg),
    /** Kiwix's other book packs, the nautilus libraries (Food for Preppers, Water, Prunelle), offered as books (Plan KB). */
    KIWIX_BOOKS(R.string.sources_scope_kiwix_books),
}

/** The scopes a pack can be in: where Readiness's domain views and the other cross-scope lists look (Plan KB Decision 6). */
val PACK_SCOPES = setOf(CatalogScope.KIWIX, CatalogScope.KIWIX_BOOKS, CatalogScope.GUTENBERG)

/**
 * Models (and voices) are Models; a Project Gutenberg pack is Gutenberg's (Plan GB: its books are the way in, so it's
 * offered under Books, not among Kiwix's knowledge packs); any other book pack (`content: "books"`, the nautilus
 * libraries) is Kiwix libraries' (Plan KB Decision 6); any other pack with a mirror on kiwix.org is Kiwix's; every
 * other pack is Granger's own.
 */
fun scopeOf(item: CatalogItem): CatalogScope = when (item) {
    is ModelEntry, is VoiceEntry -> CatalogScope.MODELS
    // Books are Granger's own.
    is BookEntry -> CatalogScope.GRANGER
    is PackEntry -> when {
        isGutenberg(item) -> CatalogScope.GUTENBERG
        item.isBookPack -> CatalogScope.KIWIX_BOOKS
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
