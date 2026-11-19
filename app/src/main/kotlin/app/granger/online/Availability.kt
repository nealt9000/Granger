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
        /** From an article's copies ([ArticleCopies.copies]). Whether the network is up now doesn't change the answer. */
        fun of(copies: List<ArticleCopy>): Availability = when {
            copies.any { it.kind == ArticleCopy.Kind.FULL } -> OnPhone(full = true)
            copies.any { it.kind == ArticleCopy.Kind.SHORT } -> OnPhone(full = false)
            copies.any { it.kind == ArticleCopy.Kind.ONLINE } -> Online
            else -> Nowhere
        }
    }
}

/** [Availability] for articles, one at a time or a screen's worth, remembered for the screen's life. */
class ArticleAvailability(private val copies: ArticleCopies, private val packs: suspend () -> List<app.granger.core.library.Pack>) {
    private val known = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Availability>()

    @Volatile private var lastPacks: List<app.granger.core.library.Pack>? = null

    suspend fun of(packId: String, path: String): Availability {
        val all = packs()
        // A pack came or went, or an online source: worked out again.
        if (all != lastPacks) { known.clear(); lastPacks = all }
        return known.getOrPut(packId to path) { compute(packId, path, all) }
    }

    private suspend fun compute(packId: String, path: String, all: List<app.granger.core.library.Pack>): Availability {
        val lang = ArticleCopies.languageOf(packId, all.firstOrNull { it.id == packId })
        val answer = if (lang == null) {
            // Not a Wikipedia: the pack's own copy or nothing.
            all.firstOrNull { it.id == packId && !OnlineSources.isOnline(it.id) }?.let { Availability.OnPhone(ArticleCopies.kindOf(it) == ArticleCopy.Kind.FULL) } ?: Availability.Nowhere
        } else {
            Availability.of(copies.copies(packId, path, lang, all).filter { c -> c.kind == ArticleCopy.Kind.ONLINE || all.any { it.id == c.packId } })
        }
        return answer
    }

    suspend fun of(articles: List<Pair<String, String>>): Map<Pair<String, String>, Availability> = articles.associateWith { (p, a) -> of(p, a) }
}
