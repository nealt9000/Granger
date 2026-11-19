package app.granger.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.formatBytes

/**
 * What it would take to have an article that can't open now (spec §7, Plan O3 Task 6; builder's call 5): keep it next time
 * online (about [keepBytes]), or get the [pack] that holds it.
 */
data class WouldTake(val packId: String, val path: String, val title: String, val keepBytes: Long, val pack: PackOffer?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WouldTakeSheet(take: WouldTake, onKeepLater: () -> Unit, onGetPack: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).testTag(WouldTakeTags.SHEET)) {
            Text(stringResource(R.string.would_take_title, take.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.would_take_keep, formatBytes(take.keepBytes))) },
                supportingContent = { Text(stringResource(R.string.would_take_keep_summary)) },
                modifier = Modifier.testTag(WouldTakeTags.KEEP).clickable { onKeepLater(); onDismiss() },
            )
            take.pack?.let { pack ->
                ListItem(
                    headlineContent = { Text(stringResource(R.string.would_take_pack, pack.title, formatBytes(pack.sizeBytes))) },
                    supportingContent = { Text(stringResource(R.string.would_take_pack_summary)) },
                    modifier = Modifier.testTag(WouldTakeTags.PACK).clickable { onGetPack(pack.id); onDismiss() },
                )
            }
        }
    }
}

object WouldTakeTags {
    const val SHEET = "would_take"
    const val KEEP = "would_take_keep"
    const val PACK = "would_take_pack"
}
