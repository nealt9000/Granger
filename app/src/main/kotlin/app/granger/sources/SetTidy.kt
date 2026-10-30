package app.granger.sources

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.formatBytes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The offer to tidy up once a bigger slice of a set is in (Plan WF Decision 5): "Top 1 million is in. Remove Best 50,000
 * (2.7 GB)? Your places and bookmarks move across." [key] names the pair, so a Keep is remembered.
 */
data class TidyOffer(
    val key: String,
    val bigId: String,
    val smallId: String,
    val bigPackId: String,
    val smallPackId: String,
    val smallBytes: Long,
    val text: String,
    /** The smaller slice in a few words, for the button: "Best 50,000". */
    val smallLabel: String = "",
)

/** The offers Keep has answered, kept across launches (SharedPreferences `"home"`, beside Home's own marks). */
class TidyDismissals(initial: Set<String> = emptySet(), private val save: (Set<String>) -> Unit = {}) {
    private val mutable = MutableStateFlow(initial)
    val keys: StateFlow<Set<String>> = mutable

    fun dismiss(key: String) {
        mutable.value = mutable.value + key
        save(mutable.value)
    }

    companion object {
        private const val KEY = "dismissedTidy"

        fun shared(prefs: android.content.SharedPreferences) =
            TidyDismissals(prefs.getStringSet(KEY, null).orEmpty().toSet()) { prefs.edit().putStringSet(KEY, it).apply() }
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
                    KiwixList.shortLabel(small.setLabel),
                )
            }.filterNot { it.key in dismissed }
        }
    }

    /** "Top 1 million is in. Remove Best 50,000 (2.7 GB)? Your places and bookmarks move across." */
    fun text(big: PackEntry, small: PackEntry, smallBytes: Long): String =
        "${KiwixList.shortLabel(big.setLabel)} is in. Remove ${KiwixList.shortLabel(small.setLabel)} (${formatBytes(smallBytes)})? " +
            "Your places and bookmarks move across."

    /** Yes: the smaller slice gives way to the bigger, its places and bookmarks moving across. False when it couldn't. */
    suspend fun remove(offer: TidyOffer, library: Library, downloads: Downloads): Boolean {
        if (!library.supersede(offer.smallPackId, offer.bigPackId, acrossNames = true)) return false
        downloads.remove(offer.smallId)
        return true
    }
}

/** One tidy offer as a card: its sentence, then Keep both and Remove (Plan WF Decision 5). Shared by Home and Downloads. */
@Composable
fun TidyOfferCard(offer: TidyOffer, onRemove: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).testTag("tidy-offer")) {
        Text(offer.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onKeep) { Text("Keep both") }
            TextButton(onClick = onRemove) { Text("Remove ${offer.smallLabel}".trim()) }
        }
    }
}

/** Each installed pack's set and rank (Plan WF Decision 6), from the current catalog by the pack's ZIM name. */
class CatalogPackSlices(private val catalog: () -> Catalog?) : app.granger.core.library.PackSlices {
    @Volatile private var cached: Pair<Catalog, Map<String, app.granger.core.library.SliceKey>>? = null

    override fun of(pack: Pack): app.granger.core.library.SliceKey? {
        val current = catalog() ?: return null
        val table = cached?.takeIf { it.first === current }?.second
            ?: current.packs.filter { it.isSlice }
                .associate { it.zimName to app.granger.core.library.SliceKey(it.setId, it.setRank) }
                .also { cached = current to it }
        return table[pack.name]
    }
}
