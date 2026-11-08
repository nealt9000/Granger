package app.granger.home

import java.net.URI

/** How a page's `src` or `href` becomes a ZIM path; images ([ArticleThumbnails]) and links ([LeadLinks]) share it. */
internal object ZimPaths {
    /**
     * [ref] resolved against [articlePath]: `../I/x.jpg` from `A/Cat` and `I/x.jpg` from `Cat` both give `I/x.jpg`;
     * percent-escapes are decoded (`x%20y` → `x y`). A query or fragment is dropped.
     */
    fun resolve(ref: String, articlePath: String): String {
        val clean = ref.substringBefore('#').substringBefore('?')
        val base = runCatching { URI(null, null, "/$articlePath", null) }.getOrNull()
        // "Halo:_Reach" would parse as a scheme; a page's link is always relative, so read it as "./Halo:_Reach".
        val relative = runCatching { URI(clean.replace(" ", "%20")) }.getOrNull()?.let { if (it.isAbsolute) URI("./" + it.toString()) else it }
        val resolved = runCatching { relative?.let { base?.resolve(it) } }.getOrNull()
        val path = resolved?.path?.removePrefix("/") ?: clean.removePrefix("./")
        return path.replace("%20", " ")
    }
}
