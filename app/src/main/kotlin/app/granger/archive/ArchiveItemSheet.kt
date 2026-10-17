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
import app.granger.core.archive.Offer
import app.granger.formatBytes
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** The label for what an item offers; null when the guess is unknown. */
internal fun offerLabel(offer: Offer?): String? = when (offer) {
    is Offer.Download -> when (offer.kind) {
        app.granger.core.archive.TextKind.EPUB -> "EPUB"
        app.granger.core.archive.TextKind.TEXT -> "Plain text"
        app.granger.core.archive.TextKind.OCR_TEXT -> "OCR text: may have errors"
    }
    Offer.PdfOnly -> "PDF: coming soon"
    Offer.LendOnly -> "Borrow on archive.org"
    Offer.Nothing -> "No downloadable text"
    null -> null
}

/** An item's details with Get, Borrow or View, and Remove. [onGet] already asked for notifications. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveItemSheet(
    sheet: ItemSheet,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
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
                val label = offerLabel(sheet.offer)
                if (label != null) {
                    Text(
                        listOfNotNull(label, sheet.sizeBytes?.let { formatBytes(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            ItemStatusText(sheet.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetButtons(sheet, onGet, onCancel, onRetry, onRemove, onOpenInBrowser)
            }
            val offer = sheet.offer
            if (offer is Offer.Download && offer.alsoPdf) {
                Text("PDF: coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onOpenInBrowser) { Text("View on archive.org") }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SheetButtons(
    sheet: ItemSheet,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    // The ledger decides first: something installed or in flight is managed whatever the metadata offers now.
    when (sheet.status) {
        is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
        is ItemStatus.Failed -> {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onRetry) { Text("Retry") }
        }
        ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text("Remove") }
        else -> when (sheet.offer) {
            is Offer.Download -> Button(onClick = onGet) { Text("Get") }
            Offer.LendOnly -> Button(onClick = onOpenInBrowser) { Text("Borrow on archive.org") }
            else -> Unit
        }
    }
}
