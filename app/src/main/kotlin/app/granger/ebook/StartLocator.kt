package app.granger.ebook

import app.granger.BookTarget
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** Where a book opens. */
object StartLocator {
    /**
     * In order: the route's [target] (a search hit), the saved place, the saved place's progression through the
     * book (a rebuilt edition renamed its chapter files), else null for the start. Never hands Readium an href
     * outside the reading order, and never throws on a garbled saved locator.
     */
    suspend fun choose(publication: Publication, savedJson: String?, target: BookTarget?): Locator? {
        if (target != null) {
            // Targets come from the book index: decoded zip paths ("OEBPS/Chapter 1.xhtml"), not URLs.
            val path = target.href.substringBefore('#')
            val fragment = target.href.substringAfter('#', "").takeIf { it.isNotEmpty() }
            val link = path.takeIf { it.isNotEmpty() }?.let { Url.fromDecodedPath(it) }?.let { publication.chapterFor(it) }
            if (link != null) {
                return Locator(
                    // The reading order's own href; a #fragment the target pointed at goes in the locations.
                    href = link.url(),
                    mediaType = link.mediaType ?: MediaType.XHTML,
                    locations = Locator.Locations(
                        fragments = listOfNotNull(fragment),
                        progression = target.progression,
                    ),
                )
            }
        }
        return repair(publication, parse(savedJson) ?: return null)
    }

    /**
     * [stored] if its chapter is in [publication]'s reading order; else the place at its progression through the
     * book (a rebuilt edition renamed its chapter files), if that lands in the reading order; else null.
     */
    suspend fun repair(publication: Publication, stored: Locator): Locator? {
        if (publication.chapterFor(stored.href) != null) return stored
        val total = stored.locations.totalProgression ?: return null
        return try {
            publication.locateProgression(total.coerceIn(0.0, 1.0))
                ?.takeIf { publication.chapterFor(it.href) != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: String?): Locator? {
        if (json.isNullOrBlank()) return null
        return try {
            Locator.fromJSON(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

}

/** The reading-order item [href] points into; hrefs compare without their #fragment, normalised alike. */
internal fun Publication.chapterFor(href: Url): Link? {
    val wanted = href.removeFragment()
    return readingOrder.firstOrNull { it.url().removeFragment().isEquivalent(wanted) }
}

/** Whether two hrefs name the same file, ignoring #fragments. The one comparison the reader uses. */
internal fun sameFile(a: Url, b: Url): Boolean = a.removeFragment().isEquivalent(b.removeFragment())

/**
 * Whether two locators are the same place: the same file, and the same page position. Without a position on both
 * sides, the progression through the book decides, within [PLACE_TOLERANCE].
 */
internal fun isSamePlace(a: Locator, b: Locator): Boolean {
    if (!sameFile(a.href, b.href)) return false
    val pa = a.locations.position
    val pb = b.locations.position
    if (pa != null && pb != null) return pa == pb
    val ta = a.locations.totalProgression ?: return false
    val tb = b.locations.totalProgression ?: return false
    return kotlin.math.abs(ta - tb) <= PLACE_TOLERANCE
}

private const val PLACE_TOLERANCE = 0.0005
