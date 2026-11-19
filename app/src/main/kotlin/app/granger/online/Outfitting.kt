package app.granger.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.KeptArticle
import app.granger.explore.Eyebrow
import app.granger.formatBytes
import app.granger.readiness.OutfittingSlots

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

/**
 * Outfitting's own sections (spec §8, Plan O4 builder's call 2), around Readiness's domains and files: Try Local only and
 * Your use above, On this phone (kept articles, Saved for now) above storage.
 */
fun outfittingSlots(state: OutfittingState, actions: OutfittingActions): OutfittingSlots = OutfittingSlots(
    head = {
        item(key = "try-local") { TryLocalOnly() }
        state.ready?.let { ui -> item(key = "your-use") { YourUse(ui, actions) } }
    },
    onPhone = { onPhone(state, actions) },
)

@Composable
private fun TryLocalOnly() {
    val control = LocalReach.current ?: return
    val state by control.state.collectAsState()
    ListItem(
        headlineContent = { Text(stringResource(R.string.outfitting_try_local)) },
        supportingContent = { Text(stringResource(R.string.outfitting_try_local_summary)) },
        trailingContent = {
            Switch(
                checked = state == ReachState.LOCAL_ONLY || state == ReachState.NEVER, enabled = state != ReachState.NEVER,
                onCheckedChange = control::setLocalOnly, modifier = Modifier.testTag(OutfittingTags.TRY_LOCAL),
            )
        },
    )
}

@Composable
private fun YourUse(ui: ReadyOfflineUi, actions: OutfittingActions) {
    Column(Modifier.padding(top = 8.dp).testTag(OutfittingTags.YOUR_USE), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Home's Ready offline, at full size: every subject, and the suggestions under it.
        app.granger.home.ReadyOfflineSection(ui.copy(keepTheRead = emptyList(), pack = null), app.granger.home.ReadyActions(), seeAll = false)
        for (s in ui.suggestions) {
            ListItem(
                headlineContent = {
                    Text(
                        when {
                            s.packId == null -> stringResource(R.string.outfitting_keep_read, s.covers, formatBytes(s.bytes))
                            s.about -> stringResource(R.string.outfitting_suggest_pack_about, s.title, formatBytes(s.bytes), s.covers)
                            else -> stringResource(R.string.outfitting_suggest_pack, s.title, formatBytes(s.bytes), s.covers)
                        },
                    )
                },
                modifier = Modifier.testTag(OutfittingTags.SUGGESTION).clickable {
                    if (s.packId == null) actions.onKeepTheRead(s.articles) else actions.onGetPack(s.packId)
                },
            )
        }
    }
}

private fun LazyListScope.onPhone(state: OutfittingState, actions: OutfittingActions) {
    item(key = "on-phone") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
            Eyebrow(stringResource(R.string.outfitting_on_phone))
            Text(stringResource(R.string.outfitting_kept_articles), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
    if (state.updates.isNotEmpty()) item(key = "kept-updates") {
        ListItem(
            headlineContent = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.outfitting_updates, state.updates.size, state.updates.size)) },
            supportingContent = { Text(state.updates.joinToString(", ") { WikimediaClient.titleOfKey(it.title) }, maxLines = 2) },
            trailingContent = { TextButton(onClick = { actions.onUpdate(state.updates) }) { Text(stringResource(R.string.outfitting_update_all)) } },
            modifier = Modifier.testTag(OutfittingTags.UPDATES),
        )
    }
    if (state.kept.isEmpty()) item(key = "kept-none") {
        Text(stringResource(R.string.outfitting_nothing_kept), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    items(state.kept, key = { "kept-${it.lang}-${it.title}" }) { a ->
        ListItem(
            headlineContent = { Text(WikimediaClient.titleOfKey(a.title)) },
            supportingContent = { Text(formatBytes(a.bytes) + if (a.withPictures) "" else " · " + stringResource(R.string.settings_offline_pictures_text)) },
            trailingContent = { TextButton(onClick = { actions.onRemoveKept(a) }) { Text(stringResource(R.string.outfitting_remove)) } },
            modifier = Modifier.clickable { actions.onOpenKept(a) }.testTag(OutfittingTags.KEPT),
        )
    }
    item(key = "saved") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.outfitting_saved, formatBytes(state.savedBytes), formatBytes(state.savedCapBytes))) },
            trailingContent = if (state.savedBytes > 0) ({ TextButton(onClick = actions.onClearSaved) { Text(stringResource(R.string.settings_saved_clear_confirm)) } }) else null,
            modifier = Modifier.testTag(OutfittingTags.SAVED),
        )
    }
}

object OutfittingTags {
    const val TRY_LOCAL = "outfitting_try_local"
    const val YOUR_USE = "outfitting_your_use"
    const val SUGGESTION = "outfitting_suggestion"
    const val KEPT = "outfitting_kept"
    const val SAVED = "outfitting_saved"
    const val UPDATES = "outfitting_updates"
}
