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
            val href = Url(target.href)
            val link = href?.let { publication.chapterFor(it) }
            if (href != null && link != null) {
                return Locator(
                    // The reading order's own href; a #fragment the target pointed at goes in the locations.
                    href = link.url(),
                    mediaType = link.mediaType ?: MediaType.XHTML,
                    locations = Locator.Locations(
                        fragments = listOfNotNull(href.fragment),
                        progression = target.progression,
                    ),
                )
            }
        }
        val saved = parse(savedJson) ?: return null
        if (publication.chapterFor(saved.href) != null) return saved
        val total = saved.locations.totalProgression ?: return null
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

    /** The reading-order item [href] points into; hrefs compare without their #fragment, normalised alike. */
    private fun Publication.chapterFor(href: Url): Link? {
        val wanted = href.removeFragment()
        return readingOrder.firstOrNull { it.url().removeFragment().isEquivalent(wanted) }
    }
}
