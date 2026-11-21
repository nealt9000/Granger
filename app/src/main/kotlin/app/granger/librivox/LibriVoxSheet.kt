package app.granger.librivox

import app.granger.invoke
import app.granger.resolve
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.UiText
import app.granger.core.archive.LibriVoxOffer
import app.granger.formatBytes
import app.granger.shell.rememberRailCover
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** "Read by A, B, C and 2 more": the first three readers, then how many more; null with none. */
internal fun readersLabel(readers: List<String>): UiText? {
    if (readers.isEmpty()) return null
    val shown = readers.take(3).joinToString(", ")
    val more = readers.size - 3
    return if (more > 0) UiText.Plural(R.plurals.librivox_read_by_more, more, shown, more) else UiText.Res(R.string.librivox_read_by, shown)
}

/** Why a book can't be got, or null when it can. */
internal fun unavailableReason(offer: LibriVoxOffer?): UiText? = when (offer) {
    is LibriVoxOffer.Incomplete -> UiText.Res(R.string.librivox_incomplete)
    LibriVoxOffer.NotOnArchive -> UiText.Res(R.string.librivox_not_on_archive)
    is LibriVoxOffer.Files, null -> null
}

/** A book's details with Get, Cancel, Retry, Remove or Get the rest, and View on LibriVox. [onGet] already asked for notifications. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibriVoxBookSheet(
    sheet: LibriVoxSheet,
    fallbackTitle: String?,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val book = sheet.book
            val cover = rememberRailCover(sheet.coverPath, 120.dp)
            if (cover != null) {
                Image(
                    cover, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)),
                )
            }
            Text(
                book?.title?.let(::decodeEntities)?.ifBlank { null } ?: fallbackTitle ?: stringResource(R.string.librivox_book_fallback),
                style = MaterialTheme.typography.titleLarge,
            )
            when {
                sheet.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                sheet.error != null -> Text(sheet.error.resolve(), color = MaterialTheme.colorScheme.error)
            }
            if (book != null) {
                if (book.authors.isNotEmpty()) {
                    Text(decodeEntities(book.authors.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                }
                readersLabel(book.sections.flatMap { it.readers }.distinct().map(::decodeEntities))?.let {
                    Text(it.resolve(), style = MaterialTheme.typography.bodySmall)
                }
                val facts = listOfNotNull(book.language.ifBlank { null }, durationLabel(book.totalTimeMs)?.resolve())
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (book.genres.isNotEmpty()) {
                    Text(
                        book.genres.joinToString(" · ") { decodeEntities(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (book.description.isNotBlank()) {
                    Text(
                        decodeEntities(book.description), maxLines = 6, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val files = sheet.offer as? LibriVoxOffer.Files
                if (files != null) {
                    val n = files.files.size
                    Text(
                        sheet.sizeBytes?.let { pluralStringResource(R.plurals.librivox_chapters_size, n, n, formatBytes(it)) }
                            ?: pluralStringResource(R.plurals.librivox_chapters, n, n),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                unavailableReason(sheet.offer)?.let {
                    if (sheet.status == ItemStatus.NotInstalled) Text(it.resolve(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val downloading = sheet.status is ItemStatus.Queued || sheet.status is ItemStatus.Downloading
            if (downloading && sheet.filesTotal > 0) {
                Text(pluralStringResource(R.plurals.librivox_downloading_chapters, sheet.filesTotal, sheet.filesDone, sheet.filesTotal), style = MaterialTheme.typography.bodyMedium)
            }
            ItemStatusText(sheet.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (sheet.status) {
                    is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    is ItemStatus.Failed -> {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                        Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                    }
                    ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                    is ItemStatus.Partial -> {
                        TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
                        Button(onClick = onGet) { Text(stringResource(R.string.librivox_get_the_rest)) }
                    }
                    else -> if (sheet.offer is LibriVoxOffer.Files) Button(onClick = onGet) { Text(stringResource(R.string.common_get)) }
                }
            }
            val page = book?.pageUrl?.takeIf { it.isNotBlank() }
            if (page != null) OutlinedButton(onClick = { onOpenPage(page) }) { Text(stringResource(R.string.librivox_view_on_site)) }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}
