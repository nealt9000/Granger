package app.granger.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.granger.library.ConfirmDialog
import app.granger.sources.ItemStatus

/** What a downloaded book or album page says about its files; null when there's nothing to say (all in). */
internal fun downloadText(status: ItemStatus, done: Int, total: Int, unit: String): String? = when (status) {
    is ItemStatus.Queued, is ItemStatus.Downloading -> if (total > 0) "Downloading: $done of $total $unit" else "Downloading"
    is ItemStatus.Partial -> "Partly downloaded: ${status.done} of ${status.total} $unit"
    is ItemStatus.Failed -> status.reason
    else -> null
}

/**
 * A downloaded book's or album's progress line and **Remove download**, which asks first. [unit] is "chapters" or
 * "tracks". Phone media never shows this.
 */
@Composable
fun DownloadControls(title: String, status: ItemStatus, done: Int, total: Int, unit: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        downloadText(status, done, total, unit)?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall,
                color = if (status is ItemStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { confirming = true }) { Text("Remove download") }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Remove $title?",
            message = "This deletes the downloaded files. Your place and bookmarks are kept.",
            confirm = "Remove",
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
    }
}
