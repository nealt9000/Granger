package app.granger.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.formatBytes
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** The label for what an item offers; null when the guess is unknown. */
internal fun offerLabel(offer: Offer?): String? = when (offer) {
    is Offer.Download -> when (offer.kind) {
        app.granger.core.archive.TextKind.EPUB -> if (offer.pdf != null) "EPUB made from a scan · PDF" else "EPUB"
        app.granger.core.archive.TextKind.TEXT -> "Plain text"
        app.granger.core.archive.TextKind.OCR_TEXT -> if (offer.pdf != null) "OCR text: may have errors · PDF" else "OCR text: may have errors"
        app.granger.core.archive.TextKind.PDF -> "PDF"
        app.granger.core.archive.TextKind.COMIC -> "Comic: its pages, to zoom"
    }
    Offer.LendOnly -> "Borrow on archive.org"
    Offer.Nothing -> "No downloadable text"
    null -> null
}

/** The label for what an audio item offers: its format on a row, "7 tracks · VBR MP3" once its files are known. */
internal fun audioLabel(offer: AudioOffer?): String? = when (offer) {
    is AudioOffer.Tracks ->
        if (offer.files.isEmpty()) offer.format
        else "${offer.files.size} ${if (offer.files.size == 1) "track" else "tracks"} · ${offer.format}"
    AudioOffer.LendOnly -> "Borrow on archive.org"
    AudioOffer.Nothing -> "No downloadable audio"
    null -> null
}

/**
 * An item's details with Get, Borrow or View, and Remove. [onGet] already asked for notifications. [audio] is what an
 * audio item offers (texts pass null and use [ItemSheet.offer]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveItemSheet(
    sheet: ItemSheet,
    audio: AudioOffer?,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
    /** Gets the PDF beside the item's text, as a second book (Plan P2). */
    onGetPdf: () -> Unit = {},
    /** An item got from the other audio view ([ItemStatus.InLibrary]): open it there. */
    onOpenInLibrary: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val item = sheet.item
            Text(item?.title?.ifBlank { null } ?: sheet.identifier, style = MaterialTheme.typography.titleLarge)
            when {
                sheet.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                sheet.error != null -> Text(sheet.error, color = MaterialTheme.colorScheme.error)
            }
            if (item != null) {
                val byline = listOf(item.creator, item.date).filter { it.isNotBlank() }.joinToString(" · ")
                if (byline.isNotEmpty()) Text(byline, style = MaterialTheme.typography.bodyMedium)
                if (item.subjects.isNotEmpty()) {
                    Text(
                        item.subjects.take(6).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.description.isNotBlank()) {
                    Text(item.description, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                val label = audioLabel(audio) ?: offerLabel(sheet.offer)
                if (label != null) Text(label, style = MaterialTheme.typography.labelLarge)
            }
            val downloading = sheet.status is ItemStatus.Queued || sheet.status is ItemStatus.Downloading
            if (downloading && sheet.filesTotal > 0) {
                Text("Downloading: ${sheet.filesDone} of ${sheet.filesTotal}", style = MaterialTheme.typography.bodyMedium)
            }
            ItemStatusText(sheet.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetButtons(sheet, audio, onGet, onCancel, onRetry, onRemove, onOpenInBrowser, onOpenInLibrary)
                // The PDF beside the text, side by side with Get: its own download, and its own book once in.
                if (sheet.pdfSizeBytes != null && sheet.status !is ItemStatus.InLibrary) {
                    when (sheet.pdfStatus) {
                        ItemStatus.NotInstalled -> OutlinedButton(onClick = onGetPdf) { Text("Get PDF · ${formatBytes(sheet.pdfSizeBytes)}") }
                        ItemStatus.Installed, ItemStatus.UpdateAvailable ->
                            Text("PDF in your library", style = MaterialTheme.typography.bodySmall)
                        else -> Text(
                            "PDF: " + pdfStatusText(sheet.pdfStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onOpenInBrowser) { Text("View on archive.org") }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SheetButtons(
    sheet: ItemSheet,
    audio: AudioOffer?,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenInLibrary: () -> Unit,
) {
    // The ledger decides first: something installed or in flight is managed whatever the metadata offers now.
    when (sheet.status) {
        is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
        is ItemStatus.Failed -> {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onRetry) { Text("Retry") }
        }
        ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text("Remove") }
        is ItemStatus.Partial -> {
            TextButton(onClick = onRemove) { Text("Remove") }
            Button(onClick = onGet) { Text("Get the rest") }
        }
        is ItemStatus.InLibrary -> Button(onClick = onOpenInLibrary) { Text("Open") }
        else -> when {
            audio is AudioOffer.Tracks -> Button(onClick = onGet) { Text("Get") }
            audio == AudioOffer.LendOnly -> Button(onClick = onOpenInBrowser) { Text("Borrow on archive.org") }
            sheet.offer is Offer.Download -> Button(onClick = onGet) {
                Text(sheet.sizeBytes?.let { "Get · ${formatBytes(it)}" } ?: "Get")
            }
            sheet.offer == Offer.LendOnly -> Button(onClick = onOpenInBrowser) { Text("Borrow on archive.org") }
            else -> Unit
        }
    }
}

/** Where the PDF's own download stands, in a few words; the sheet's Cancel and Retry act on both downloads. */
private fun pdfStatusText(status: ItemStatus): String = when (status) {
    is ItemStatus.Queued -> "queued"
    is ItemStatus.Downloading -> "downloading, ${(status.fraction * 100).toInt()}%"
    is ItemStatus.Failed -> "failed"
    else -> ""
}
