package app.granger.online

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.granger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the hints' "seen" marks live; an interface so tests keep them in memory. */
interface HintStore {
    fun seen(key: String): Boolean
    fun mark(key: String, seen: Boolean)
}

class SharedHintStore(private val prefs: SharedPreferences) : HintStore {
    constructor(context: Context) : this(context.getSharedPreferences("hints", Context.MODE_PRIVATE))
    override fun seen(key: String) = prefs.getBoolean(key, false)
    override fun mark(key: String, seen: Boolean) = prefs.edit().putBoolean(key, seen).apply()
}

/**
 * One-line hints, each shown once where its idea first appears (Plan OB call 5): Keep and the marks on the first online
 * topic page; Local only on the first offline banner. Dismissed by ×, Keep's also by the first Keep; Settings brings them back.
 */
class Hints(private val store: HintStore) {
    private val keepShown = MutableStateFlow(!store.seen(KEEP))
    private val offlineShown = MutableStateFlow(!store.seen(OFFLINE))

    val keep: StateFlow<Boolean> = keepShown.asStateFlow()
    val offline: StateFlow<Boolean> = offlineShown.asStateFlow()

    fun keepDone() { store.mark(KEEP, true); keepShown.value = false }
    fun offlineDone() { store.mark(OFFLINE, true); offlineShown.value = false }

    /** Settings → Show tips again. */
    fun reset() {
        store.mark(KEEP, false); store.mark(OFFLINE, false)
        keepShown.value = true; offlineShown.value = true
    }

    private companion object {
        const val KEEP = "keep"
        const val OFFLINE = "offline"
    }
}

val LocalHints = staticCompositionLocalOf<Hints?> { null }

object HintTags {
    const val KEEP = "hint_keep"
    const val OFFLINE = "hint_offline"
}

/** Above a topic's Keep: what Keep does and what the two marks mean. Only for an online article not yet kept, while online. */
@Composable
fun KeepHint(packId: String, path: String, modifier: Modifier = Modifier) {
    if (!OnlineSources.isOnline(packId)) return
    val hints = LocalHints.current ?: return
    val show by hints.keep.collectAsState()
    if (!show) return
    val reach = LocalReach.current
    if (reach != null && !reach.state.collectAsState().value.online) return
    val marks = LocalMarks.current
    if (marks != null) {
        val version by marks.version.collectAsState()
        val kept = androidx.compose.runtime.remember(packId, path, version) { marks.kept(packId, path) }
        if (kept != null) return
    }
    HintLine(stringResource(R.string.hint_keep), HintTags.KEEP, hints::keepDone, modifier)
}

/** One hint: a quiet line with ×, announced politely (Plan OB call 6). */
@Composable
internal fun HintLine(text: String, tag: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            trailing?.invoke()
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close)) }
        }
    }
}
