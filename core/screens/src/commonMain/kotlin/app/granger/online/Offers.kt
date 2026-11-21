package app.granger.online

import app.granger.core.library.KeptArticle
import app.granger.core.online.OnlineWikipediaArchive

// Plan I4 Task 2: the offers both phones make (keep a room, get a pack, what it would take) and what Outfitting works
// on. The sheets and screens that show them stay per phone.

/** What keeping a room would take (Plan O2 Task 6): its online articles not yet kept, about how much, and a pack that covers it. */
data class RoomOffer(val articles: List<Pair<String, String>>, val estimateBytes: Long, val pack: PackOffer?)

/** A Kiwix pack whose subject covers the room, offered first (builder's call 6). */
data class PackOffer(val id: String, val title: String, val sizeBytes: Long)

/** A room's offer, worked out (pure, for tests). */
object RoomOffers {
    /**
     * About what keeping one article takes: its page, and its pictures under Save pictures. Measured on the Pixel
     * (2026-11-19, 27 kept articles): 1.9 MB a mean with pictures; the prepared page alone about a quarter of a megabyte.
     */
    const val EACH_WITH_PICTURES = 1_900_000L
    const val EACH_TEXT = 250_000L

    /**
     * From the room's [ranked] articles (pack id, path), best known first: the online ones not yet kept, their estimated
     * size, and the smallest catalog pack whose subject is [subjectId] (each of [packs] is (id, title, size) to its
     * subject id). Null when there's nothing to offer.
     */
    fun build(
        ranked: List<Pair<String, String>>, isKept: (String, String) -> Boolean, keepsPictures: Boolean,
        packs: List<Pair<Triple<String, String, Long>, String?>>, subjectId: String,
    ): RoomOffer? {
        val online = ranked.mapNotNull { (packId, path) -> OnlineWikipediaArchive.languageOf(packId)?.let { it to path } }
            .filter { (l, t) -> !isKept(l, t) }.distinct()
        // A Wikipedia subject pack (WikiMed for health) covers a room best; else the smallest pack on the subject.
        val onSubject = packs.filter { it.second == subjectId }.map { it.first }
        val pack = (onSubject.filter { it.first.startsWith("wikipedia_") }.minByOrNull { it.third } ?: onSubject.minByOrNull { it.third })
            ?.let { PackOffer(it.first, it.second, it.third) }
        if (online.isEmpty() && pack == null) return null
        return RoomOffer(online, online.size * (if (keepsPictures) EACH_WITH_PICTURES else EACH_TEXT), pack)
    }
}

data class KeepProgress(val done: Int, val total: Int, val running: Boolean)

/**
 * What it would take to have an article that can't open now (spec §7, Plan O3 Task 6; builder's call 5): keep it next time
 * online (about [keepBytes]), or get the [pack] that holds it.
 */
data class WouldTake(val packId: String, val path: String, val title: String, val keepBytes: Long, val pack: PackOffer?)

/** What Outfitting works on (Plan O4 Tasks 1–2). */
class OutfittingState(
    val ready: ReadyOfflineUi?,
    val kept: List<KeptArticle>,
    val savedBytes: Long,
    val savedCapBytes: Long,
    /** Kept articles with a newer version online (Plan O5 Task 4). */
    val updates: List<KeptArticle> = emptyList(),
)

class OutfittingActions(
    val onKeepTheRead: (List<Pair<String, String>>) -> Unit = {},
    val onGetPack: (String) -> Unit = {},
    val onOpenKept: (KeptArticle) -> Unit = {},
    val onRemoveKept: (KeptArticle) -> Unit = {},
    val onClearSaved: () -> Unit = {},
    val onUpdate: (List<KeptArticle>) -> Unit = {},
)

