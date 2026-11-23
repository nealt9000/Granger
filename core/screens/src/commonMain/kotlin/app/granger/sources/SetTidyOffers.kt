package app.granger.sources

import app.granger.UiText
import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.screens.bytesText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The offer to tidy up once a bigger slice of a set is in (Plan WF Decision 5): "Top 1 million is in. Remove Best 50,000
 * (2.7 GB)? Your places and bookmarks move across." [key] names the pair, so a Keep is remembered.
 *
 * Shared with the iPhone since Plan I7 Task 7c (moved out of Android's `SetTidy.kt`, which keeps the card).
 */
data class TidyOffer(
    val key: String,
    val bigId: String,
    val smallId: String,
    val bigPackId: String,
    val smallPackId: String,
    val smallBytes: Long,
    val text: UiText,
    /** The smaller slice in a few words, for the button: "Best 50,000". */
    val smallLabel: String = "",
) {
    /** The offer's Remove button: "Remove Best 50,000", or Remove when the slice has no short name. */
    val removeLabel: UiText get() = if (smallLabel.isBlank()) UiText.Res("common_remove") else UiText.Res("sources_tidy_remove", smallLabel)

    /** The offer's other button. */
    val keepLabel: UiText get() = UiText.Res("sources_tidy_keep_both")
}

/**
 * The offers Keep has answered, kept across launches (Android: SharedPreferences `"home"`, beside Home's own marks; the
 * iPhone: its defaults). [save] is told the whole set after each Keep.
 */
class TidyDismissals(initial: Set<String> = emptySet(), private val save: (Set<String>) -> Unit = {}) {
    private val mutable = MutableStateFlow(initial)
    val keys: StateFlow<Set<String>> = mutable

    fun dismiss(key: String) {
        mutable.value = mutable.value + key
        save(mutable.value)
    }

    companion object {
        /** Where the answered offers are kept (Android's prefs key, the iPhone's defaults key). */
        const val KEY = "dismissedTidy"
    }
}

/**
 * Bigger covers smaller, tidied only on a yes (Plan WF Decision 5). Never removes anything by itself: [offers] says what
 * could go, and [remove] does it when asked.
 */
object SetTidy {
    /**
     * For each set with two slices on the phone, one offer per smaller slice, to give way to the biggest; none that
     * [dismissed] holds. Flavours of one slice are a Switch's business (Plan KB), not this.
     */
    fun offers(catalog: Catalog?, records: List<DownloadRecord>, packs: List<Pack>, dismissed: Set<String>): List<TidyOffer> {
        catalog ?: return emptyList()
        val ok = packs.filter { it.status == PackStatus.OK }
        val onPhone = catalog.packs.filter { it.isSlice }.mapNotNull { entry ->
            installedPackId(entry, records.filter { it.itemId == entry.id }, ok)?.let { entry to it }
        }
        return onPhone.groupBy { it.first.setId }.values.flatMap { slices ->
            val (big, bigPackId) = slices.maxBy { it.first.setRank }
            slices.filter { (entry, packId) -> entry.setRank < big.setRank && packId != bigPackId }.map { (small, smallPackId) ->
                val bytes = ok.firstOrNull { it.id == smallPackId }?.sizeBytes ?: small.sizeBytes
                TidyOffer(
                    "${small.id}>${big.id}", big.id, small.id, bigPackId, smallPackId, bytes, text(big, small, bytes),
                    KiwixList.shortLabel(small),
                )
            }.filterNot { it.key in dismissed }
        }
    }

    /** "Top 1 million is in. Remove Best 50,000 (2.7 GB)? Your places and bookmarks move across." */
    fun text(big: PackEntry, small: PackEntry, smallBytes: Long): UiText =
        UiText.Res("sources_tidy_text", KiwixList.shortLabel(big), KiwixList.shortLabel(small), bytesText(smallBytes))

    /** Yes: the smaller slice gives way to the bigger, its places and bookmarks moving across. False when it couldn't. */
    suspend fun remove(offer: TidyOffer, library: Library, downloads: Downloads): Boolean {
        if (!library.supersede(offer.smallPackId, offer.bigPackId, acrossNames = true)) return false
        downloads.remove(offer.smallId)
        return true
    }
}
