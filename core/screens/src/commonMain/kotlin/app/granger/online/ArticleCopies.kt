package app.granger.online

import app.granger.core.online.OnlineWikipediaArchive
import app.granger.core.library.Pack
import app.granger.core.library.QidIndexes

/** One place an article can be read: a pack's copy (full, or shorter: mini or no pictures), or online. */
data class ArticleCopy(val packId: String, val path: String, val kind: Kind) {
    enum class Kind { FULL, SHORT, ONLINE }
}

/**
 * Which copy of an article opens (spec §4, Plan O1 Task 5), for an article opened by its topic rather than inside a pack:
 * an Explore plate, a topic page's people, events and Related, Wander, the Timeline, a game, Home's cards, an online
 * search hit. In order:
 * 1. a kept full copy (a maxi pack): faster, free and private, so it wins even online;
 * 2. online, when the gate is open (the fullest experience beats a shorter kept copy);
 * 3. any kept copy (mini, no pictures);
 * 4. otherwise what was asked for; with nothing kept and no network the reader says so (O3 adds the sheet).
 * Copies are the same article by QID (the indexes), or by title for a Wikipedia pack's article no index covers.
 * Pack-to-pack links inside the reader stay in their pack.
 */
class ArticleCopies(
    private val qids: suspend () -> QidIndexes,
    /** The packs on the phone and the online sources (`OnlineSources.known`). */
    private val packs: suspend () -> List<Pack>,
    /** Wikipedia online is on and the gate is open. */
    private val online: () -> Boolean,
    /** The languages Wikipedia online reads now. */
    private val onlineLanguages: () -> Set<String>,
    /** Whether an online article is kept, and with its pictures (Plan O2 Task 3): null when it isn't kept. */
    private val kept: (lang: String, title: String) -> Boolean? = { _, _ -> null },
) {
    /** The copy to open for [packId]/[path]: itself unless the rule picks another. */
    suspend fun choose(packId: String, path: String): ArticleCopy {
        val all = packs()
        val asked = all.firstOrNull { it.id == packId }
        val lang = languageOf(packId, asked) ?: return ArticleCopy(packId, path, kindOf(asked))
        val copies = copies(packId, path, lang, all)
        return pick(ArticleCopy(packId, path, kindOf(asked)), copies, online())
    }

    /** Every copy of the article at [packId]/[path] this phone can open, the asked one included. */
    suspend fun copies(packId: String, path: String, lang: String, all: List<Pack>): List<ArticleCopy> {
        val byId = all.associateBy { it.id }
        val index = runCatching { qids() }.getOrNull()
        val qid = index?.let { q -> q.qidOf(packId, path) ?: q.qidOfTitle(path, lang) }
        val kept = if (qid != null && index != null) {
            index.articlesOf(qid, lang).mapNotNull { a -> byId[a.packId]?.takeIf { !(OnlineWikipediaArchive.languageOf(it.id) != null) }?.let { ArticleCopy(a.packId, a.path, kindOf(it)) } }
        } else emptyList()
        val title = (kept.firstOrNull()?.path ?: path).substringAfterLast('/')
        val source = OnlineWikipediaArchive.packName(lang)
        val onlineCopy = if (lang in onlineLanguages()) ArticleCopy(source, title, ArticleCopy.Kind.ONLINE) else null
        // A kept article is read through its online source, which serves the kept files first (Plan O2).
        val keptCopy = kept(lang, title)?.let { full -> ArticleCopy(source, title, if (full) ArticleCopy.Kind.FULL else ArticleCopy.Kind.SHORT) }
        val asked = byId[packId]?.takeIf { !(OnlineWikipediaArchive.languageOf(it.id) != null) }?.let { ArticleCopy(packId, path, kindOf(it)) }
        return (listOfNotNull(asked) + kept + listOfNotNull(keptCopy, onlineCopy)).distinctBy { it.packId to it.kind }
    }

    companion object {
        /** The rule, on its own (the order above). */
        fun pick(asked: ArticleCopy, copies: List<ArticleCopy>, online: Boolean): ArticleCopy =
            copies.firstOrNull { it.kind == ArticleCopy.Kind.FULL }
                ?: (if (online) copies.firstOrNull { it.kind == ArticleCopy.Kind.ONLINE } else null)
                ?: copies.firstOrNull { it.kind == ArticleCopy.Kind.SHORT }
                ?: asked

        /** A Wikipedia's language, from its online id or its ZIM name; null for any other pack. */
        fun languageOf(packId: String, pack: Pack?): String? =
            OnlineWikipediaArchive.languageOf(packId) ?: pack?.name?.let { WIKIPEDIA.find(it)?.groupValues?.get(1) }

        /** Full means a maxi pack (all its pictures); mini and no-picture packs are shorter; online is online. */
        fun kindOf(pack: Pack?): ArticleCopy.Kind = when {
            pack == null -> ArticleCopy.Kind.SHORT
            (OnlineWikipediaArchive.languageOf(pack.id) != null) -> ArticleCopy.Kind.ONLINE
            !pack.name.startsWith("wikipedia_") -> ArticleCopy.Kind.FULL
            "_maxi" in pack.filePath.substringAfterLast('/') || pack.name.endsWith("_maxi") -> ArticleCopy.Kind.FULL
            else -> ArticleCopy.Kind.SHORT
        }

        private val WIKIPEDIA = Regex("^wikipedia_([a-z]{2,3})_")
    }
}
