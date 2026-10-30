package app.granger.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.formatBytes
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** The label for what an item offers; null when the guess is unknown. */
internal fun offerLabel(offer: Offer?): String? = when (offer) {
    is Offer.Download -> kindLabel(offer) + if (offer.isSet) " · ${offer.parts.size} parts" else ""
    Offer.LendOnly -> "Borrow on archive.org"
    Offer.Nothing -> "No downloadable text"
    null -> null
}

private fun kindLabel(offer: Offer.Download): String = when (offer.kind) {
    app.granger.core.archive.TextKind.EPUB -> if (offer.pdf != null) "EPUB made from a scan · PDF" else "EPUB"
    app.granger.core.archive.TextKind.TEXT -> "Plain text"
    app.granger.core.archive.TextKind.OCR_TEXT -> if (offer.pdf != null) "OCR text: may have errors · PDF" else "OCR text: may have errors"
    app.granger.core.archive.TextKind.PDF -> "PDF"
    app.granger.core.archive.TextKind.COMIC -> "Comic: its pages, to zoom"
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
    /** A set's part, by number (Plan IA): get it, or remove it. */
    onGetPart: (Int) -> Unit = {},
    onRemovePart: (Int) -> Unit = {},
) {
    var choosing by rememberSaveable(sheet.identifier) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (choosing && sheet.parts.isNotEmpty()) {
            PartsList(sheet, onBack = { choosing = false }, onGetPart, onRemovePart, onRetry)
            return@ModalBottomSheet
        }
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
                if (label != null) Text(setLabel(label, sheet), style = MaterialTheme.typography.labelLarge)
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
            if (sheet.parts.isNotEmpty()) OutlinedButton(onClick = { choosing = true }) { Text("Choose parts") }
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
                val get = if (sheet.parts.isNotEmpty()) "Get all" else "Get"
                Text(sheet.sizeBytes?.let { "$get · ${formatBytes(it)}" } ?: get)
            }
            sheet.offer == Offer.LendOnly -> Button(onClick = onOpenInBrowser) { Text("Borrow on archive.org") }
            else -> Unit
        }
    }
}

/** On the sheet a set's offer line says how much in all too: "Comic: its pages, to zoom · 4 parts · 234 MB". */
internal fun setLabel(label: String, sheet: ItemSheet): String =
    if (sheet.parts.isEmpty()) label else label + (sheet.sizeBytes?.let { " · ${formatBytes(it)}" } ?: "")

/** More parts than this get a filter field over the list. */
internal const val PARTS_FILTER_FROM = 20

/** The parts whose title holds [query], ignoring case; all of them for a blank one. */
internal fun filterParts(parts: List<PartRow>, query: String): List<PartRow> =
    if (query.isBlank()) parts else parts.filter { it.title.contains(query.trim(), ignoreCase = true) }

/** A set's parts, each with its size and Get, where it stands, Retry or Remove (Plan IA Decision 5). */
@Composable
private fun PartsList(sheet: ItemSheet, onBack: () -> Unit, onGetPart: (Int) -> Unit, onRemovePart: (Int) -> Unit, onRetry: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("${sheet.parts.size} parts", style = MaterialTheme.typography.titleMedium)
        }
        if (sheet.parts.size > PARTS_FILTER_FROM) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find a part") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ArchiveSheetTags.PART_FILTER),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag(ArchiveSheetTags.PARTS)) {
            items(filterParts(sheet.parts, query), key = { it.number }) { part ->
                ListItem(
                    headlineContent = { Text(part.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(partStatusText(part)) },
                    trailingContent = {
                        when (part.status) {
                            ItemStatus.NotInstalled -> TextButton(onClick = { onGetPart(part.number) }) { Text("Get") }
                            is ItemStatus.Failed -> TextButton(onClick = onRetry) { Text("Retry") }
                            ItemStatus.Installed, ItemStatus.UpdateAvailable ->
                                TextButton(onClick = { onRemovePart(part.number) }) { Text("Remove") }
                            else -> Unit
                        }
                    },
                )
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

/** A part's size, and where its download stands when it's in or on its way. */
internal fun partStatusText(part: PartRow): String {
    val size = formatBytes(part.sizeBytes)
    return when (val status = part.status) {
        is ItemStatus.Queued -> "$size · queued"
        is ItemStatus.Downloading -> "$size · downloading, ${(status.fraction * 100).toInt()}%"
        is ItemStatus.Failed -> "$size · stopped: ${status.reason}"
        ItemStatus.Installed, ItemStatus.UpdateAvailable -> "$size · in your library"
        else -> size
    }
}

object ArchiveSheetTags {
    const val PARTS = "archive_sheet_parts"
    const val PART_FILTER = "archive_sheet_part_filter"
}

/** Where the PDF's own download stands, in a few words; the sheet's Cancel and Retry act on both downloads. */
private fun pdfStatusText(status: ItemStatus): String = when (status) {
    is ItemStatus.Queued -> "queued"
    is ItemStatus.Downloading -> "downloading, ${(status.fraction * 100).toInt()}%"
    is ItemStatus.Failed -> "failed"
    else -> ""
}
