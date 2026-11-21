package app.granger.sources

import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadRecord
import app.granger.core.library.Pack

// The shared ItemStatus (core:screens) asks what the phone can run; Android answers from its device tier (Plan I4b Task 6).

/** [item]'s status, with this phone's [tier] deciding which models it can run. */
fun statusOf(item: CatalogItem, records: List<DownloadRecord>, packs: List<Pack>, tier: DeviceTier): ItemStatus =
    statusOf(item, records, packs, tier::canRun)

fun DeviceTier.canRun(role: ModelRole): Boolean = when (role) {
    // Ask's model isn't offered at all while Ask rests (Plan SQ): a preset or a start leaves it out like a phone too small.
    ModelRole.SMALL -> app.granger.Features.RESEARCH && this >= DeviceTier.ASK
    ModelRole.EMBEDDER -> this >= DeviceTier.ASK
    // Gemma 4 E4B: too slow and too big on the phones measured (Plan R5 Task 8), and nothing uses it.
    ModelRole.LARGE -> false
}
