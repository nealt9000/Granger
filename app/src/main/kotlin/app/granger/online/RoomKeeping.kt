package app.granger.online

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.granger.R
import app.granger.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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

/** What the room page's Keep row works on; provided once at the top, null leaves the row out. */
interface RoomKeepSource {
    /** The offer for a room ([facetId]) or a whole subject (null); null when there's nothing online to keep. */
    suspend fun offer(subjectId: String, facetId: String?): RoomOffer?
    fun keep(roomKey: String, name: String, articles: List<Pair<String, String>>)
    fun cancel(roomKey: String)
    fun getPack(id: String)
    fun progress(roomKey: String): Flow<KeepProgress?>
}

val LocalRoomKeep = staticCompositionLocalOf<RoomKeepSource?> { null }

/**
 * A room's "Keep this room" (Plan O2 Task 6): opens a sheet offering a covering pack first, then the room's articles
 * with their count and estimated size; while they're kept it says how far it has got, with Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepRoomRow(subjectId: String, facetId: String?, name: String, modifier: Modifier = Modifier) {
    val source = LocalRoomKeep.current ?: return
    val key = "$subjectId.${facetId ?: "*"}"
    val offer by produceState<RoomOffer?>(null, key) { value = withContext(Dispatchers.IO) { runCatching { source.offer(subjectId, facetId) }.getOrNull() } }
    val progress by remember(key) { source.progress(key) }.collectAsState(null)
    var open by remember { mutableStateOf(false) }
    val p = progress
    if (p != null && p.running) {
        Row(modifier.testTag(RoomKeepTags.PROGRESS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keep_room_progress, p.done, p.total))
            TextButton(onClick = { source.cancel(key) }) { Text(stringResource(R.string.common_cancel)) }
        }
        return
    }
    val o = offer ?: return
    if (o.articles.isEmpty() && o.pack == null) return
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp).testTag(RoomKeepTags.KEEP)) { Text(stringResource(R.string.keep_room)) }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.keep_room_title, name), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                o.pack?.let { pack ->
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.keep_room_pack, pack.title, formatBytes(pack.sizeBytes))) },
                        supportingContent = { Text(stringResource(R.string.keep_room_pack_summary)) },
                        modifier = Modifier.testTag(RoomKeepTags.PACK).clickable { source.getPack(pack.id); open = false },
                    )
                }
                if (o.articles.isNotEmpty()) ListItem(
                    headlineContent = { Text(stringResource(R.string.keep_room_articles, o.articles.size, formatBytes(o.estimateBytes))) },
                    supportingContent = { Text(stringResource(R.string.keep_room_articles_summary)) },
                    modifier = Modifier.testTag(RoomKeepTags.ARTICLES).clickable { source.keep(key, name, o.articles); open = false },
                )
            }
        }
    }
}

object RoomKeepTags {
    const val KEEP = "keep_room"
    const val PACK = "keep_room_pack"
    const val ARTICLES = "keep_room_articles"
    const val PROGRESS = "keep_room_progress"
}

/**
 * Keeps a room's articles one at a time (Plan O2 Task 6; builder's call 6), in the background, resuming after a restart:
 * the ones already kept are passed over. While the network gate is shut (Local only) it waits and tries again later.
 */
class KeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? app.granger.GrangerApp)?.container ?: return Result.failure()
        val articles = inputData.getStringArray(ARTICLES).orEmpty().mapNotNull { it.split('\t').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }
        var done = articles.count { (l, t) -> container.keeper.isKept(l, t) != null }
        setProgress(workDataOf(DONE to done, TOTAL to articles.size))
        for ((lang, title) in articles) {
            if (container.keeper.isKept(lang, title) != null) continue
            if (!container.networkGate.open) return Result.retry()
            container.keeper.keep(lang, title) ?: return Result.retry()
            done++
            setProgress(workDataOf(DONE to done, TOTAL to articles.size))
        }
        return Result.success(workDataOf(DONE to done, TOTAL to articles.size))
    }

    companion object {
        const val ARTICLES = "articles"
        const val DONE = "done"
        const val TOTAL = "total"

        fun nameOf(roomKey: String) = "keep-room:$roomKey"

        fun start(context: Context, roomKey: String, articles: List<Pair<String, String>>) {
            val data = Data.Builder().putStringArray(ARTICLES, articles.map { (l, t) -> "$l\t$t" }.toTypedArray()).build()
            // Tried again soon after the internet is back, not on a growing backoff (Plan O3: a keep queued in Local only).
            val request = OneTimeWorkRequestBuilder<KeepWorker>().setInputData(data).addTag("keep-room")
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(nameOf(roomKey), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, roomKey: String) = WorkManager.getInstance(context).cancelUniqueWork(nameOf(roomKey))

        fun progress(context: Context, roomKey: String): Flow<KeepProgress?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(nameOf(roomKey)).map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                val d = if (info.state.isFinished) info.outputData else info.progress
                KeepProgress(d.getInt(DONE, 0), d.getInt(TOTAL, 0), !info.state.isFinished && info.state != WorkInfo.State.BLOCKED)
            }
    }
}
