package app.granger.readiness

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.UiText
import app.granger.core.ai.DeviceTier
import app.granger.formatBytes
import java.text.DateFormat
import java.util.Date

/** Signals shown before "Show all" (spec §4). */
const val SHOWN_SIGNALS = 3

/** The signals the screen lists: the first [SHOWN_SIGNALS] until Show all is tapped. */
fun shownSignals(signals: List<Signal>, showAll: Boolean): List<Signal> = if (showAll) signals else signals.take(SHOWN_SIGNALS)

/** Statuses that make a source say it needs attention. */
private val TROUBLE = setOf(CheckStatus.INCOMPLETE, CheckStatus.CORRUPTED, CheckStatus.MISSING, CheckStatus.DAMAGED)

/** The status line under a domain's bar. */
fun coverageText(domain: DomainCoverage): UiText {
    val held = formatBytes(domain.installedBytes)
    val essentials = formatBytes(domain.essentialBytes)
    return when (domain.coverage) {
        // A domain can hold packs outside its tiers (the bar shows them) and still have no essential.
        Coverage.NONE -> when {
            domain.installedBytes > 0L && domain.essentialBytes > 0L -> UiText.Res(R.string.readiness_coverage_held_essentials, held, essentials)
            domain.installedBytes > 0L -> UiText.Res(R.string.readiness_coverage_held, held)
            domain.essentialBytes > 0L -> UiText.Res(R.string.readiness_coverage_nothing_essentials, essentials)
            else -> UiText.Res(R.string.readiness_coverage_nothing)
        }
        Coverage.BASIC -> UiText.Res(R.string.readiness_coverage_basic, held, formatBytes(domain.comprehensiveBytes))
        Coverage.COMPREHENSIVE -> UiText.Res(R.string.readiness_coverage_comprehensive, held)
    }
}

/** [onOpenKiwix] opens the Kiwix source screen filtered to a domain, or the whole of it for null. */
@Composable
fun ReadinessScreen(viewModel: ReadinessViewModel, onBack: () -> Unit, onOpenKiwix: (domain: String?) -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ReadinessContent(ui, onBack, onOpenKiwix, viewModel::verify, viewModel::repair)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessContent(
    ui: ReadinessUi,
    onBack: () -> Unit,
    onOpenKiwix: (domain: String?) -> Unit,
    onVerify: (String) -> Unit,
    onRepair: (String) -> Unit,
) {
    var showAllSignals by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.readiness_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("readiness-list")) {
            item(key = "device") {
                Heading(stringResource(R.string.readiness_this_phone))
                ListItem(
                    headlineContent = { Text(stringResource(tierText(ui.tier))) },
                    supportingContent = { Text(stringResource(R.string.readiness_memory, formatBytes(ui.totalRamBytes))) },
                )
            }

            item(key = "coverage") { Heading(stringResource(R.string.readiness_what_you_hold)) }
            if (ui.report.needsCatalogRefresh) item(key = "coverage-refresh") { Empty(stringResource(R.string.readiness_refresh_catalog)) }
            items(ui.report.domains, key = { "domain-${it.id}" }) { domain ->
                ListItem(
                    headlineContent = { Text(domain.title) },
                    supportingContent = {
                        Column {
                            DomainBar(
                                domain.installedBytes, domain.essentialBytes, domain.comprehensiveBytes, domain.coverage,
                                Modifier.padding(top = 6.dp, bottom = 4.dp),
                            )
                            Text(coverageText(domain).resolve(), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = Modifier.clickable { onOpenKiwix(domain.id) },
                )
            }
            val signals = shownSignals(ui.report.signals, showAllSignals)
            itemsIndexed(signals, key = { i, _ -> "signal-$i" }) { _, signal ->
                ListItem(
                    headlineContent = { Text(signal.text.resolve()) },
                    trailingContent = {
                        when (signal) {
                            is Signal.EmptyDomain -> TextButton(onClick = { onOpenKiwix(signal.domain) }) { Text(stringResource(R.string.readiness_see)) }
                            is Signal.Stale -> TextButton(onClick = { onOpenKiwix(signal.domain) }) { Text(stringResource(R.string.readiness_see)) }
                            is Signal.Unsearchable -> {}
                        }
                    },
                )
            }
            if (!showAllSignals && ui.report.signals.size > SHOWN_SIGNALS) {
                item(key = "signals-more") {
                    TextButton(onClick = { showAllSignals = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.readiness_show_all, ui.report.signals.size))
                    }
                }
            }

            item(key = "storage") { Heading(stringResource(R.string.readiness_storage)) }
            items(ui.volumes, key = { "volume-${it.path}" }) { volume ->
                ListItem(
                    headlineContent = { Text(volume.label) },
                    supportingContent = {
                        Text(stringResource(R.string.readiness_volume, formatBytes(volume.freeBytes), formatBytes(volume.totalBytes), formatBytes(volume.grangerBytes)))
                    },
                )
            }
            // Each source lists its own files, checks included, behind a tap: one list of everything was too long (Plan H4).
            val files = (ui.models + ui.voices + ui.packs + ui.media).groupBy { it.source }
            val bySource = ui.report.storage.filter { it.bytes > 0L || it.referencedOnly || !files[it.source].isNullOrEmpty() }
            if (bySource.isNotEmpty()) item(key = "by-source") { Heading(stringResource(R.string.readiness_by_source)) }
            for (row in bySource) {
                val rows = files[row.source].orEmpty()
                val open = row.source.name in expanded
                item(key = "source-${row.source.name}") {
                    val trouble = rows.count { it.status in TROUBLE }
                    ListItem(
                        headlineContent = { Text(stringResource(row.source.title)) },
                        supportingContent = {
                            Text(
                                sourceText(row, trouble).resolve(),
                                color = if (trouble > 0) MaterialTheme.colorScheme.error else Color.Unspecified,
                            )
                        },
                        trailingContent = if (rows.isEmpty()) null else {
                            {
                                Icon(
                                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(if (open) R.string.readiness_hide_source else R.string.readiness_show_source, stringResource(row.source.title)),
                                )
                            }
                        },
                        modifier = if (rows.isEmpty()) Modifier else Modifier.clickable {
                            expanded = if (open) expanded - row.source.name else expanded + row.source.name
                        },
                    )
                }
                if (open) {
                    items(rows.sortedBy { it.title.lowercase() }, key = { it.key }) { check ->
                        Check(check, onVerify, onRepair)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun tierText(tier: DeviceTier) = when (tier) {
    DeviceTier.LIBRARY_ONLY -> R.string.readiness_tier_library_only
    DeviceTier.ASK -> R.string.readiness_tier_ask
}

/** A source's line: "Not stored by Granger", or its size and file count, and how many need attention. */
private fun sourceText(row: StorageRow, trouble: Int): UiText {
    if (row.referencedOnly) return UiText.Res(R.string.readiness_not_stored)
    val items = UiText.Plural(R.plurals.readiness_source_items, row.items, formatBytes(row.bytes), row.items)
    return if (trouble > 0) UiText.Plural(R.plurals.readiness_source_trouble, trouble, items, trouble) else items
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Check(row: CheckRow, onVerify: (String) -> Unit, onRepair: (String) -> Unit) {
    val (text, good) = when (row.status) {
        CheckStatus.VERIFIED -> {
            val date = row.verifiedAt?.let { DateFormat.getDateInstance().format(Date(it)) }
            (if (date != null) stringResource(R.string.readiness_verified_on, date) else stringResource(R.string.readiness_verified)) to true
        }
        CheckStatus.SIZE_OK -> stringResource(R.string.readiness_size_ok) to true
        CheckStatus.INCOMPLETE -> stringResource(R.string.readiness_incomplete) to false
        CheckStatus.CORRUPTED -> stringResource(R.string.readiness_corrupted) to false
        CheckStatus.MISSING -> stringResource(R.string.readiness_missing) to false
        CheckStatus.DAMAGED -> stringResource(R.string.readiness_damaged) to false
        CheckStatus.NOT_FROM_CATALOG -> stringResource(R.string.readiness_not_from_catalog) to true
    }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(row.snapshotDate.ifBlank { null }, formatBytes(row.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text, color = if (good) Color.Unspecified else MaterialTheme.colorScheme.error)
                row.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(stringResource(R.string.readiness_checking, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row {
                if (row.canVerify) TextButton(onClick = { onVerify(row.key) }) { Text(stringResource(R.string.readiness_check)) }
                if (row.canRepair) TextButton(onClick = { onRepair(row.key) }) { Text(stringResource(R.string.readiness_download_again)) }
            }
        },
    )
}
