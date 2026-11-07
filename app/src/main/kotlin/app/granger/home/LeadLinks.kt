package app.granger.home

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** An article's opening (Plan H5a, Decisions 2 and 5): its `<title>`, a short plain summary, and the links Wander orbits. */
data class Lead(val title: String, val summary: String, val links: List<String>)

/**
 * Reads the lead of a Wikipedia page as the packs store it: the paragraphs before the first `h2`, at most [PARAGRAPHS]
 * with real text. Links are ZIM paths ([ZimPaths.resolve]) ranked by where they first appear and how often; years,
 * dates, centuries, files and namespaced pages are left out. English and Spanish pages follow the same rules.
 */
object LeadLinks {
    private const val PARAGRAPHS = 3
    private const val MIN_TEXT = 60
    private const val MAX_LINKS = 12
    const val MAX_SUMMARY = 300

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    ).joinToString("|")

    /** Pages about a span of time rather than a subject: 1066, 1990s, 44 BC, AD 79, 15th century, Siglo XV, July 4, 4 de julio. */
    private val TIME_PAGE = Regex(
        "^(?:AD_)?\\d{1,4}(?:s|_BC|_BCE|_AD|_a\\._C\\.)?$" +
            "|^\\d{1,2}(?:st|nd|rd|th)_(?:century|millennium)(?:_BC)?$" +
            "|^[Ss]iglo_[IVXLC]+(?:_a\\._C\\.)?$" +
            "|^(?:$MONTHS)(?:_\\d{1,2})?$" +
            "|^\\d{1,2}_de_(?:$MONTHS)$" +
            "|^(?:$MONTHS)_de_\\d{1,4}$",
        RegexOption.IGNORE_CASE,
    )

    /** A pronunciation in brackets: IPA between slashes, or a "listen" / "pronunciation" link. */
    private val SAID = Regex("\\s*\\((?:[^()]*?/[^()/]+/[^()]*?|[^()]*?(?:listen|pronunciation|pronunciación|escuchar)[^()]*?)\\)", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[\"“¿¡(]?[\\p{Lu}])")

    fun parse(html: String, articlePath: String): Lead {
        val doc = Jsoup.parse(html)
        val title = doc.title().ifBlank { doc.selectFirst("h1")?.text().orEmpty() }.trim()
        doc.select("sup, .reference, .mw-editsection, style, script, table, figure, .hatnote, .thumb, .infobox, .navbox, .metadata").remove()
        val paragraphs = ArrayList<Element>()
        for (el in doc.body().select("h2, p")) {
            if (el.tagName() == "h2") break
            if (el.text().trim().length >= MIN_TEXT) paragraphs.add(el)
            if (paragraphs.size >= PARAGRAPHS) break
        }
        val self = articlePath.substringAfterLast('/')
        val scores = LinkedHashMap<String, Int>()
        paragraphs.forEachIndexed { i, p ->
            for (a in p.select("a[href]")) {
                val path = linkPath(a.attr("href"), articlePath) ?: continue
                if (path.substringAfterLast('/') == self) continue
                val first = path !in scores
                scores[path] = (scores[path] ?: 0) + if (first) (if (i == 0) 3 else 1) else 1
            }
        }
        // Stable: equal scores keep the order they appear in.
        val links = scores.entries.sortedByDescending { it.value }.map { it.key }.take(MAX_LINKS)
        return Lead(title, summary(paragraphs), links)
    }

    /** [href] as a ZIM article path, or null for a link Wander shouldn't orbit. */
    internal fun linkPath(href: String, articlePath: String): String? {
        val h = href.trim()
        if (h.isEmpty() || h.startsWith("#") || h.startsWith("//") || SCHEME.containsMatchIn(h)) return null
        val path = ZimPaths.resolve(h, articlePath).ifBlank { return null }
        val name = path.substringAfterLast('/')
        if (name.isEmpty() || NAMESPACE.containsMatchIn(name)) return null     // File:, Help:, Archivo:; not "Halo:_Reach"
        if (path.startsWith("I/") || path.startsWith("-/") || path.startsWith("_")) return null
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in FILE_EXTENSIONS) return null
        if (TIME_PAGE.matches(name)) return null
        if (app.granger.core.library.IndexPages.isIndex(path)) return null
        return path
    }

    private val SCHEME = Regex("^(?:https?|ftp|mailto|tel|geo|javascript|data):", RegexOption.IGNORE_CASE)

    /** Wiki namespaces, English and Spanish; an article title may hold a colon too ("Star_Trek:_Voyager"). */
    private val NAMESPACE = Regex(
        "^(?:File|Image|Media|Help|Wikipedia|Category|Template|Portal|Special|Talk|User|Module|Draft|MediaWiki|" +
            "Archivo|Imagen|Ayuda|Categoría|Plantilla|Especial|Discusión|Usuario|Usuaria|Módulo|Wikiproyecto)(?:_talk)?:",
        RegexOption.IGNORE_CASE,
    )

    private val FILE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "svg", "webp", "pdf", "ogg", "ogv", "webm", "mp3", "css", "js")

    /** The lead's first two sentences, plain, at most [MAX_SUMMARY] characters, cut at a word. */
    internal fun summary(paragraphs: List<Element>): String {
        val text = paragraphs.joinToString(" ") { it.text() }
            .replace(SAID, "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;:!?)])"), "$1")
            .replace("( ", "(")
            .trim()
        if (text.isEmpty()) return ""
        val two = text.split(SENTENCE_END).take(2).joinToString(" ")
        if (two.length <= MAX_SUMMARY) return two
        val cut = two.take(MAX_SUMMARY - 1)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > MAX_SUMMARY / 2 } ?: cut.length).trimEnd(',', ';', ':', ' ') + "…"
    }
}
