package app.granger.ebook

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/** The contents row of an EPUB [here] ([TocPlace] is shared, on hrefs; the files are compared as Readium's Urls). */
internal fun TocPlace.index(toc: List<TocRow>, here: Locator, anchorStarts: Map<String, Map<String, Double>>): Int? =
    index(toc, { href -> Url(href)?.let { sameFile(it, here.href) } == true }, here.locations.progression, anchorStarts)
