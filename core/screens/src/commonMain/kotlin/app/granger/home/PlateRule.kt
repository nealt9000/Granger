package app.granger.home

import app.granger.core.library.putIfMissing
import app.granger.core.text.ZimPaths
import app.granger.core.text.percentDecoded
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * The picture rule for an article's card and plate (Plan EX P0, rule v4, graded with Neal 2026-11-17), shared since
 * Plan I3 so both phones pick the same picture. Android's `ArticleThumbnails` reads and scales what it picks.
 */
object PlateRule {
    const val MIN_SIDE = 64
    private val SKIPPED_EXTENSIONS = setOf("svg", "ico")

    /** How many of an article's pictures the rule looks at, and how many it will read before giving up. */
    private const val CONSIDERED = 8
    const val MAX_TRIED = 4
    private const val ROOMY_WIDTH = 120
    private const val ROOMY_HEIGHT = 100

    private fun word(s: String) = "(?<![a-z])(?:$s)(?![a-z])"

    /** A picture whose file name says flag, seal, map or the like: never the photo a series symbol gives way to. */
    private val EMBLEM = Regex(
        listOf(
            word("flags?"), "coat[_ ]of[_ ]arms", word("coa"), "seal[_ ]of", word("emblem"), word("escudo"), word("bandera"), word("wappen"),
            word("blason"), "locator", "location[_ ]map", word("maps?"), "orthographic", word("projection"), word("signature"), "diaspora",
            word("insignia"), word("crest"), word("karte"), word("carte"), "carta[_ ](?:nav|geo|de)", word("charts?"),
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    /** An article about a symbol itself (Flag of Washington, Coat of arms of…): its drawn symbol is its picture. */
    private val SYMBOL_TOPIC =
        Regex(word("flags?|coat of arms|seal|emblem|maps?|logo|insignia|banner|symbols?|heraldry|cartography|atlas"), RegexOption.IGNORE_CASE)

    private class Found(val src: String, val name: String, val width: Int?, val height: Int?, val kind: String, val drawing: Boolean) {
        val emblem get() = EMBLEM.containsMatchIn(name) || kind == "chrome" || (kind == "sidebar" && drawing)
        val jpeg get() = name.lowercase().removeSuffix(".webp").substringAfterLast('.', "") in setOf("jpg", "jpeg")
        val roomy get() = (width ?: 0) >= ROOMY_WIDTH && (height ?: 0) >= ROOMY_HEIGHT
        val wide get() = (width ?: 0) >= (height ?: 0)
    }

    private fun decoded(s: String): String = percentDecoded(s) ?: s

    /** What holds the picture, by its nearest telling ancestor: a figure, the infobox, a series sidebar, page chrome… */
    private fun kind(img: Element): String {
        for (a in img.parents()) {
            val cls = a.className()
            val table = a.tagName() == "table"
            return when {
                a.tagName() == "figure" -> "figure"
                "infobox-image" in cls -> "infobox"
                "sidebar" in cls -> "sidebar"
                "multiimage" in cls || "tsingle" in cls -> "multi"
                "gallery" in cls -> "gallery"
                "navbox" in cls || "metadata" in cls || "ambox" in cls || "noprint" in cls -> "chrome"
                table -> "table"
                else -> continue
            }
        }
        return "other"
    }

    /**
     * The ZIM paths that may hold the article's lead image, best first (the picture rule, Plan EX P0, graded with Neal
     * 2026-11-17): the article's pictures in page order, skipping icons, formulas, data URIs and anything drawn
     * under 64 px; except that when the first is a series sidebar's drawn symbol (a Dharma wheel on every Buddhism
     * page, a coat of arms on "Constitution of…") and the article isn't about a symbol, the first roomy JPEG photo among
     * the first eight goes ahead of it, wide before tall. Each picture gives two paths: resolved against the article's
     * path (`../I/x.jpg` from `A/Cat`, `I/x.jpg` from `Cat`), then the bare `I/<name>` form, for either layout.
     */
    fun candidates(html: String, articlePath: String): List<String> = ranked(html, articlePath).map { it.first }


    /**
     * Whether a thumbnail made by [thumbnail] is of a drawing or an emblem (a flag, a seal, a logo, a map, a diagram):
     * one to show whole on a plain ground, never cropped (Plan EX P0).
     */
    fun isWhole(thumbnail: String?): Boolean = app.granger.explore.ThumbnailNames.isWhole(thumbnail)

    /**
     * The top of an article's HTML that a card needs (the performance pass): through its lead (to the first `<h2`) and
     * far enough to hold every picture the rule looks at. Parsing a long article whole, twice, was most of a card's
     * tenth of a second; its top gives the same summary and the same picture. The whole of [html] when it is short,
     * has no heading, or has few pictures. The pictures are counted here from the tags alone, with the rule's own
     * tests and a few to spare, so the cut never falls short of what [ranked] would reach.
     */
    fun head(html: String): String {
        val lead = html.indexOf("<h2")
        if (lead < 0 || html.length < SHORT_HTML) return html
        var kept = 0
        var at = html.indexOf("<img")
        while (at >= 0) {
            val end = html.indexOf('>', at)
            if (end < 0) return html
            if (mayCount(html.substring(at, end + 1)) && ++kept == CONSIDERED + SPARE) return html.substring(0, maxOf(end + 1, lead))
            at = html.indexOf("<img", end)
        }
        return html
    }

    /**
     * Whether an `<img>` tag is one [ranked] surely counts. A tag this can't read for certain (an attribute not in
     * double quotes, no `src`) says no: counting too few only makes the cut later.
     */
    private fun mayCount(tag: String): Boolean {
        if (LOOSE.containsMatchIn(tag)) return false
        val src = SRC.find(tag)?.groupValues?.get(1)?.trim() ?: return false
        if (src.isEmpty() || src.startsWith("data:")) return false
        if (src.substringAfterLast('.', "").substringBefore('?').lowercase() in SKIPPED_EXTENSIONS) return false
        val cls = CLASS.find(tag)?.groupValues?.get(1)?.lowercase().orEmpty()
        if ("icon" in cls || "mwe-math" in cls || "noviewer" in cls) return false
        val w = WIDTH.find(tag)?.groupValues?.get(1)?.toIntOrNull()
        val h = HEIGHT.find(tag)?.groupValues?.get(1)?.toIntOrNull()
        return !((w != null && w < MIN_SIDE) || (h != null && h < MIN_SIDE))
    }

    /** An attribute whose value doesn't open with a double quote. */
    private val LOOSE = Regex("=\\s*[^\"\\s]")
    private val SRC = Regex("(?<![-\\w])src\\s*=\\s*\"([^\"]*)\"")
    private val CLASS = Regex("(?<![-\\w])class\\s*=\\s*\"([^\"]*)\"")
    private val WIDTH = Regex("(?<![-\\w])width\\s*=\\s*\"(\\d+)\"")
    private val HEIGHT = Regex("(?<![-\\w])height\\s*=\\s*\"(\\d+)\"")
    /** Pictures counted past the rule's eight before the cut, in case a tag here counts one the rule skips. */
    private const val SPARE = 4
    /** An article shorter than this is parsed whole: there is little to save. */
    private const val SHORT_HTML = 40_000

    /** [candidates], each with whether it is a drawing or an emblem. */
    fun ranked(html: String, articlePath: String): List<Pair<String, Boolean>> {
        val doc = Ksoup.parse(html)
        val found = ArrayList<Found>()
        for (img in doc.select("img[src]")) {
            val src = img.attr("src").trim()
            if (src.isEmpty() || src.startsWith("data:")) continue
            val ext = src.substringAfterLast('.', "").substringBefore('?').lowercase()
            if (ext in SKIPPED_EXTENSIONS) continue
            val cls = img.className().lowercase()
            if ("icon" in cls || "mwe-math" in cls || "noviewer" in cls) continue
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            if ((w != null && w < MIN_SIDE) || (h != null && h < MIN_SIDE)) continue
            found += Found(src, decoded(decoded(src)).substringAfterLast('/'), w, h, kind(img), img.attr("data-file-type") == "drawing")
            if (found.size >= CONSIDERED) break
        }
        val first = found.firstOrNull() ?: return emptyList()
        val title = decoded(articlePath).substringAfterLast('/').replace('_', ' ')
        val ahead = if (first.kind == "sidebar" && first.drawing && !SYMBOL_TOPIC.containsMatchIn(title)) {
            found.drop(1).filter { !it.drawing && !it.emblem && it.jpeg && it.roomy }.let { photos -> photos.firstOrNull { it.wide } ?: photos.firstOrNull() }
        } else null
        val out = LinkedHashMap<String, Boolean>()
        for (f in listOfNotNull(ahead) + found) {
            val whole = f.drawing || EMBLEM.containsMatchIn(f.name)
            out.putIfMissing(ZimPaths.resolve(f.src, articlePath), whole)
            out.putIfMissing("I/" + f.src.substringAfterLast('/'), whole)
        }
        return out.toList()
    }
}
