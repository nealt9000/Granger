package app.granger.librivox

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.archive.LibriVoxOffer
import app.granger.formatBytes
import app.granger.shell.rememberRailCover
import app.granger.sources.ItemStatus
import app.granger.sources.ItemStatusText

private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

private val NAMED = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "aacute" to "á", "agrave" to "à", "acirc" to "â",
    "auml" to "ä", "aring" to "å", "ccedil" to "ç", "iacute" to "í", "icirc" to "î", "iuml" to "ï", "oacute" to "ó",
    "ocirc" to "ô", "ouml" to "ö", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "ntilde" to "ñ", "szlig" to "ß",
    "Eacute" to "É", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "aelig" to "æ", "oelig" to "œ",
)

/**
 * LibriVox text with its HTML entities decoded, once ("&amp;lt;" is "&lt;"). The client strips the tags but not the
 * entities. An unknown name, or a number that isn't a character, stays as written.
 */
internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        val decoded = when {
            body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::codePoint)
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[body]
        }
        decoded ?: match.value
    }
}

private fun codePoint(cp: Int): String? =
    if (cp > 0 && Character.isValidCodePoint(cp) && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

/** "Read by A, B, C and 2 more": the first three readers, then how many more; null with none. */
internal fun readersLabel(readers: List<String>): String? {
    if (readers.isEmpty()) return null
    val shown = readers.take(3).joinToString(", ")
    val more = readers.size - 3
    return if (more > 0) "Read by $shown and $more more" else "Read by $shown"
}

/** Why a book can't be got, or null when it can. */
internal fun unavailableReason(offer: LibriVoxOffer?): String? = when (offer) {
    is LibriVoxOffer.Incomplete -> "This book's files aren't all on archive.org."
    LibriVoxOffer.NotOnArchive -> "This book isn't on archive.org yet."
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
                book?.title?.let(::decodeEntities)?.ifBlank { null } ?: fallbackTitle ?: "LibriVox book",
                style = MaterialTheme.typography.titleLarge,
            )
            when {
                sheet.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                sheet.error != null -> Text(sheet.error, color = MaterialTheme.colorScheme.error)
            }
            if (book != null) {
                if (book.authors.isNotEmpty()) {
                    Text(decodeEntities(book.authors.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
                }
                readersLabel(book.sections.flatMap { it.readers }.distinct().map(::decodeEntities))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                val facts = listOfNotNull(book.language.ifBlank { null }, durationLabel(book.totalTimeMs))
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
                        listOfNotNull("$n ${if (n == 1) "chapter" else "chapters"}", sheet.sizeBytes?.let { formatBytes(it) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                unavailableReason(sheet.offer)?.let {
                    if (sheet.status == ItemStatus.NotInstalled) Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val downloading = sheet.status is ItemStatus.Queued || sheet.status is ItemStatus.Downloading
            if (downloading && sheet.filesTotal > 0) {
                Text("Downloading: ${sheet.filesDone} of ${sheet.filesTotal} chapters", style = MaterialTheme.typography.bodyMedium)
            }
            ItemStatusText(sheet.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    else -> if (sheet.offer is LibriVoxOffer.Files) Button(onClick = onGet) { Text("Get") }
                }
            }
            val page = book?.pageUrl?.takeIf { it.isNotBlank() }
            if (page != null) OutlinedButton(onClick = { onOpenPage(page) }) { Text("View on LibriVox") }
            Box(Modifier.padding(bottom = 16.dp))
        }
    }
}
