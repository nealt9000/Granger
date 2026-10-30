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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.UiText
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.Offer
import app.granger.formatBytes
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

/** The label for what an item offers; null when the guess is unknown. EPUB and PDF are format names, the same in every language. */
@Composable
internal fun offerLabel(offer: Offer?): String? = when (offer) {
    is Offer.Download -> if (offer.isSet) pluralStringResource(R.plurals.archive_offer_parts, offer.parts.size, offer.parts.size, kindLabel(offer)) else kindLabel(offer)
    Offer.LendOnly -> stringResource(R.string.archive_borrow)
    Offer.Nothing -> stringResource(R.string.archive_no_text)
    null -> null
}

@Composable
private fun kindLabel(offer: Offer.Download): String = when (offer.kind) {
    app.granger.core.archive.TextKind.EPUB ->
        if (offer.pdf != null) stringResource(R.string.archive_offer_epub_scan_pdf) else "EPUB"
    app.granger.core.archive.TextKind.TEXT -> stringResource(R.string.archive_offer_plain_text)
    app.granger.core.archive.TextKind.OCR_TEXT ->
        stringResource(if (offer.pdf != null) R.string.archive_offer_ocr_pdf else R.string.archive_offer_ocr)
    app.granger.core.archive.TextKind.PDF -> "PDF"
    app.granger.core.archive.TextKind.COMIC -> stringResource(R.string.archive_offer_comic)
}

/** The label for what an audio item offers: its format on a row, "7 tracks · VBR MP3" once its files are known. */
@Composable
internal fun audioLabel(offer: AudioOffer?): String? = when (offer) {
    is AudioOffer.Tracks ->
        if (offer.files.isEmpty()) offer.format
        else pluralStringResource(R.plurals.archive_tracks, offer.files.size, offer.files.size, offer.format)
    AudioOffer.LendOnly -> stringResource(R.string.archive_borrow)
    AudioOffer.Nothing -> stringResource(R.string.archive_no_audio)
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
                sheet.error != null -> Text(sheet.error.resolve(), color = MaterialTheme.colorScheme.error)
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
                Text(stringResource(R.string.archive_downloading_files, sheet.filesDone, sheet.filesTotal), style = MaterialTheme.typography.bodyMedium)
            }
            ItemStatusText(sheet.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetButtons(sheet, audio, onGet, onCancel, onRetry, onRemove, onOpenInBrowser, onOpenInLibrary)
                // The PDF beside the text, side by side with Get: its own download, and its own book once in.
                if (sheet.pdfSizeBytes != null && sheet.status !is ItemStatus.InLibrary) {
                    when (sheet.pdfStatus) {
                        ItemStatus.NotInstalled -> OutlinedButton(onClick = onGetPdf) { Text(stringResource(R.string.archive_get_pdf, formatBytes(sheet.pdfSizeBytes))) }
                        ItemStatus.Installed, ItemStatus.UpdateAvailable ->
                            Text(stringResource(R.string.archive_pdf_in_library), style = MaterialTheme.typography.bodySmall)
                        else -> Text(
                            pdfStatusText(sheet.pdfStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (sheet.parts.isNotEmpty()) OutlinedButton(onClick = { choosing = true }) { Text(stringResource(R.string.archive_choose_parts)) }
            OutlinedButton(onClick = onOpenInBrowser) { Text(stringResource(R.string.archive_view_on_site)) }
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
        is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        is ItemStatus.Failed -> {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
        ItemStatus.Installed, ItemStatus.UpdateAvailable -> TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
        is ItemStatus.Partial -> {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
            Button(onClick = onGet) { Text(stringResource(R.string.archive_get_the_rest)) }
        }
        is ItemStatus.InLibrary -> Button(onClick = onOpenInLibrary) { Text(stringResource(R.string.common_open)) }
        else -> when {
            audio is AudioOffer.Tracks -> Button(onClick = onGet) { Text(stringResource(R.string.common_get)) }
            audio == AudioOffer.LendOnly -> Button(onClick = onOpenInBrowser) { Text(stringResource(R.string.archive_borrow)) }
            sheet.offer is Offer.Download -> Button(onClick = onGet) {
                val all = sheet.parts.isNotEmpty()
                Text(
                    sheet.sizeBytes?.let { stringResource(if (all) R.string.archive_get_all_size else R.string.archive_get_size, formatBytes(it)) }
                        ?: stringResource(if (all) R.string.archive_get_all else R.string.common_get),
                )
            }
            sheet.offer == Offer.LendOnly -> Button(onClick = onOpenInBrowser) { Text(stringResource(R.string.archive_borrow)) }
            else -> Unit
        }
    }
}

/** On the sheet a set's offer line says how much in all too: "Comic: its pages, to zoom · 4 parts · 234 MB". */
@Composable
internal fun setLabel(label: String, sheet: ItemSheet): String =
    if (sheet.parts.isEmpty()) label else sheet.sizeBytes?.let { stringResource(R.string.archive_label_size, label, formatBytes(it)) } ?: label

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
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(pluralStringResource(R.plurals.archive_parts, sheet.parts.size, sheet.parts.size), style = MaterialTheme.typography.titleMedium)
        }
        if (sheet.parts.size > PARTS_FILTER_FROM) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.archive_find_part)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ArchiveSheetTags.PART_FILTER),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag(ArchiveSheetTags.PARTS)) {
            items(filterParts(sheet.parts, query), key = { it.number }) { part ->
                ListItem(
                    headlineContent = { Text(part.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(partStatusText(part).resolve()) },
                    trailingContent = {
                        when (part.status) {
                            ItemStatus.NotInstalled -> TextButton(onClick = { onGetPart(part.number) }) { Text(stringResource(R.string.common_get)) }
                            is ItemStatus.Failed -> TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                            ItemStatus.Installed, ItemStatus.UpdateAvailable ->
                                TextButton(onClick = { onRemovePart(part.number) }) { Text(stringResource(R.string.common_remove)) }
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
internal fun partStatusText(part: PartRow): UiText {
    val size = formatBytes(part.sizeBytes)
    return when (val status = part.status) {
        is ItemStatus.Queued -> UiText.Res(R.string.archive_part_queued, size)
        is ItemStatus.Downloading -> UiText.Res(R.string.archive_part_downloading, size, (status.fraction * 100).toInt())
        is ItemStatus.Failed -> UiText.Res(R.string.archive_part_stopped, size, app.granger.sources.failedText(status))
        ItemStatus.Installed, ItemStatus.UpdateAvailable -> UiText.Res(R.string.archive_part_in_library, size)
        else -> UiText.Raw(size)
    }
}

object ArchiveSheetTags {
    const val PARTS = "archive_sheet_parts"
    const val PART_FILTER = "archive_sheet_part_filter"
}

/** Where the PDF's own download stands, in a few words; the sheet's Cancel and Retry act on both downloads. */
@Composable
private fun pdfStatusText(status: ItemStatus): String = when (status) {
    is ItemStatus.Queued -> stringResource(R.string.archive_pdf_queued)
    is ItemStatus.Downloading -> stringResource(R.string.archive_pdf_downloading, (status.fraction * 100).toInt())
    is ItemStatus.Failed -> stringResource(R.string.archive_pdf_failed)
    else -> stringResource(R.string.archive_pdf_other)
}
