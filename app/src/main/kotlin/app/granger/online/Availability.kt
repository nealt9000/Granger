package app.granger.online

/**
 * Where an article can be read from (spec §3, Plan O1 Task 9): the one answer O2's marks, O3's grey-outs and O4's
 * counts read, so no screen decides it on its own.
 */
sealed interface Availability {
    /** A pack on the phone holds it; [full] when that copy has all its pictures (a maxi pack). */
    data class OnPhone(val full: Boolean) : Availability
    /** Opened lately and kept in the Saved for now cache (O2; never answered before then). */
    data object Saved : Availability
    /** Only online. */
    data object Online : Availability
    /** Not on the phone, and Wikipedia online is off for its language. */
    data object Nowhere : Availability

    companion object {
        /**
         * From an article's copies ([ArticleCopies.copies]) and whether it's [saved] for now. Whether the network is up
         * doesn't change the answer.
         */
        fun of(copies: List<ArticleCopy>, saved: Boolean = false): Availability = when {
            copies.any { it.kind == ArticleCopy.Kind.FULL } -> OnPhone(full = true)
            copies.any { it.kind == ArticleCopy.Kind.SHORT } -> OnPhone(full = false)
            saved -> Saved
            copies.any { it.kind == ArticleCopy.Kind.ONLINE } -> Online
            else -> Nowhere
        }
    }
}

/** [Availability] for articles, one at a time or a screen's worth, remembered for the screen's life. */
class ArticleAvailability(
    private val copies: ArticleCopies,
    private val packs: suspend () -> List<app.granger.core.library.Pack>,
    /** Whether an online article is saved for now (Plan O2 Task 1). */
    private val saved: (lang: String, title: String) -> Boolean = { _, _ -> false },
) {
    /**
     * Not remembered: what's saved for now changes with every article read, and the answer is a few local lookups (the
     * indexes, the kept and saved indexes).
     */
    suspend fun of(packId: String, path: String): Availability = compute(packId, path, packs())

    private suspend fun compute(packId: String, path: String, all: List<app.granger.core.library.Pack>): Availability {
        val lang = ArticleCopies.languageOf(packId, all.firstOrNull { it.id == packId })
        val answer = if (lang == null) {
            // Not a Wikipedia: the pack's own copy or nothing.
            all.firstOrNull { it.id == packId && !OnlineSources.isOnline(it.id) }?.let { Availability.OnPhone(ArticleCopies.kindOf(it) == ArticleCopy.Kind.FULL) } ?: Availability.Nowhere
        } else {
            val found = copies.copies(packId, path, lang, all)
            val title = (found.firstOrNull { OnlineSources.isOnline(it.packId) }?.path ?: path).substringAfterLast('/')
            Availability.of(found.filter { c -> OnlineSources.isOnline(c.packId) || all.any { it.id == c.packId } }, saved(lang, title))
        }
        return answer
    }

    suspend fun of(articles: List<Pair<String, String>>): Map<Pair<String, String>, Availability> {
        val all = packs()
        return articles.associateWith { (p, a) -> compute(p, a, all) }
    }
}
