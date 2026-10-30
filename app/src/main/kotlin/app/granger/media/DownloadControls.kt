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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.UiText
import app.granger.library.ConfirmDialog
import app.granger.sources.ItemStatus
import app.granger.sources.failedText

/** What a downloaded item's files are: an album's tracks or an audiobook's chapters. */
enum class DownloadUnit { TRACKS, CHAPTERS }

/** What a downloaded book or album page says about its files; null when there's nothing to say (all in). */
internal fun downloadText(status: ItemStatus, done: Int, total: Int, unit: DownloadUnit): UiText? {
    val tracks = unit == DownloadUnit.TRACKS
    fun count(trackId: Int, chapterId: Int, d: Int = done, t: Int = total) = UiText.Plural(if (tracks) trackId else chapterId, t, d, t)
    return when (status) {
        is ItemStatus.Queued -> when {
            status.lastError != null ->
                if (total > 0) count(R.plurals.media_retrying_tracks, R.plurals.media_retrying_chapters) else UiText.Res(R.string.media_retrying)
            status.wifiOnly ->
                if (total > 0) count(R.plurals.media_waiting_wifi_tracks, R.plurals.media_waiting_wifi_chapters) else UiText.Res(R.string.media_waiting_wifi)
            else -> if (total > 0) {
                count(R.plurals.media_waiting_connection_tracks, R.plurals.media_waiting_connection_chapters)
            } else {
                UiText.Res(R.string.media_waiting_connection)
            }
        }
        is ItemStatus.Downloading ->
            if (total > 0) count(R.plurals.media_downloading_tracks, R.plurals.media_downloading_chapters) else UiText.Res(R.string.common_downloading)
        is ItemStatus.Partial -> count(R.plurals.media_partial_tracks, R.plurals.media_partial_chapters, status.done, status.total)
        // The downloader's own reason (the ledger holds it as English text, L2), else Download failed.
        is ItemStatus.Failed -> failedText(status)
        else -> null
    }
}

/**
 * A downloaded book's or album's progress line and **Remove download**, which asks first. [unit] names its files.
 * Phone media never shows this.
 */
@Composable
fun DownloadControls(title: String, status: ItemStatus, done: Int, total: Int, unit: DownloadUnit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        downloadText(status, done, total, unit)?.let {
            Text(
                it.resolve(), style = MaterialTheme.typography.bodySmall,
                color = if (status is ItemStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.media_remove_download)) }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.media_remove_title, title),
            message = stringResource(R.string.media_remove_message),
            confirm = stringResource(R.string.common_remove),
            onConfirm = onRemove,
            onDismiss = { confirming = false },
        )
    }
}
