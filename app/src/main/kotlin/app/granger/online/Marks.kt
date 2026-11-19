package app.granger.online

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.granger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where marks get their answers (Plan O2 Task 4): the app's [ArticleAvailability], and a count that moves on Keep/Remove. */
interface MarkSource {
    suspend fun of(packId: String, path: String): Availability
    /** Moves whenever something is kept or removed, so marks on screen are worked out again. */
    val version: StateFlow<Int>

    /** Whether an online article is kept, and with its pictures; null when it isn't (Plan O2 Task 5). */
    fun kept(packId: String, path: String): Boolean? = null

    /** About what keeping it takes, in bytes, under the setting in force; null when not known before it's fetched. */
    suspend fun keepSize(packId: String, path: String): Long? = null

    /** Keeps an online article; false when it couldn't be had. */
    suspend fun keep(packId: String, path: String): Boolean = false

    suspend fun remove(packId: String, path: String) {}

    /** Whether the setting in force keeps pictures (the button says "Keep" or "Keep text"). */
    fun keepsPictures(): Boolean = true

    /** Whether a keep of this article is queued for when the internet is back (Plan O3 Task 6). */
    fun queued(packId: String, path: String): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)
}

/** The app's mark source, provided once at the top; null (tests, release before O5) draws no marks. */
val LocalMarks = staticCompositionLocalOf<MarkSource?> { null }

/**
 * An article's mark (spec §3, builder's call 1): a filled dot for on this phone, a ring for saved for now, nothing for
 * online (the common case). 12 dp in the accent colour; TalkBack reads its state.
 */
@Composable
fun AvailabilityMark(packId: String, path: String, modifier: Modifier = Modifier) {
    val source = LocalMarks.current ?: return
    val version by source.version.collectAsState()
    val state by produceState<Availability?>(null, packId, path, version) {
        value = withContext(Dispatchers.IO) { runCatching { source.of(packId, path) }.getOrNull() }
    }
    Mark(state, modifier)
}

/** The mark itself, for a known [state]. */
@Composable
fun Mark(state: Availability?, modifier: Modifier = Modifier) {
    val label = when (state) {
        is Availability.OnPhone -> stringResource(if (state.full) R.string.mark_on_phone else R.string.mark_on_phone_shorter)
        Availability.Saved -> stringResource(R.string.mark_saved)
        else -> return
    }
    val accent = MaterialTheme.colorScheme.primary
    val ground = MaterialTheme.colorScheme.background
    Box(
        modifier.size(SIZE).semantics { contentDescription = label }.testTag(if (state is Availability.OnPhone) MarkTags.ON_PHONE else MarkTags.SAVED)
            .then(
                if (state is Availability.OnPhone) Modifier.background(accent, CircleShape).border(1.5.dp, ground, CircleShape)
                else Modifier.background(ground, CircleShape).border(2.dp, accent, CircleShape),
            ),
    )
}

object MarkTags {
    const val ON_PHONE = "mark_on_phone"
    const val SAVED = "mark_saved"
    const val NEEDS_INTERNET = "needs_internet"
}

private val SIZE = 12.dp

/**
 * The header's Keep button for an online article (Plan O2 Task 5): "Keep · 180 KB" (or "Keep text" under Text only),
 * then "Kept" with Remove beside it. Nothing for a pack's article (it's on the phone already) or without a [LocalMarks].
 */
@Composable
fun KeepButton(packId: String, path: String, modifier: Modifier = Modifier) {
    if (!OnlineSources.isOnline(packId)) return
    val source = LocalMarks.current ?: return
    val version by source.version.collectAsState()
    val kept = androidx.compose.runtime.remember(packId, path, version) { source.kept(packId, path) }
    val queued by androidx.compose.runtime.remember(packId, path) { source.queued(packId, path) }.collectAsState(false)
    val size by produceState<Long?>(null, packId, path, version) { value = withContext(Dispatchers.IO) { runCatching { source.keepSize(packId, path) }.getOrNull() } }
    var working by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var failed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val min = modifier.heightIn(min = 48.dp)
    if (kept != null) {
        androidx.compose.material3.OutlinedButton(
            onClick = { scope.launch { working = true; source.remove(packId, path); working = false } }, enabled = !working,
            modifier = min.testTag(KeepTags.REMOVE),
        ) {
            androidx.compose.material3.Text(stringResource(if (kept) R.string.keep_kept_remove else R.string.keep_kept_text_remove))
        }
    } else {
        val pictures = source.keepsPictures()
        val words = stringResource(if (pictures) R.string.keep_article else R.string.keep_article_text)
        androidx.compose.material3.OutlinedButton(
            onClick = { scope.launch { working = true; failed = !source.keep(packId, path); working = false } }, enabled = !working,
            modifier = min.testTag(KeepTags.KEEP),
        ) {
            androidx.compose.material3.Text(
                when {
                    queued -> stringResource(R.string.keep_queued)
                    working -> stringResource(R.string.keep_working)
                    failed -> stringResource(R.string.keep_failed)
                    size != null -> stringResource(R.string.keep_with_size, words, app.granger.formatBytes(size!!))
                    else -> words
                },
            )
        }
    }
}

object KeepTags {
    const val KEEP = "keep_article"
    const val REMOVE = "keep_remove"
}

/**
 * Whether an article opens now (Plan O3 Task 4): on the phone or saved for now, or anything while online. Null until
 * known; true without [LocalMarks] or [LocalReach] (nothing is greyed then).
 */
@Composable
fun rememberOpenable(packId: String, path: String): Boolean? {
    val source = LocalMarks.current ?: return true
    val reach = LocalReach.current ?: return true
    val state by reach.state.collectAsState()
    if (state.online) return true
    val version by source.version.collectAsState()
    val availability by produceState<Availability?>(null, packId, path, version) {
        value = withContext(Dispatchers.IO) { runCatching { source.of(packId, path) }.getOrNull() }
    }
    return availability?.let { openable(it, state) }
}

/** The small label on a greyed item (Plan O3 Task 4; builder's call 3). */
@Composable
fun NeedsInternetLabel(modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        stringResource(R.string.needs_internet), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp).testTag(MarkTags.NEEDS_INTERNET),
    )
}
