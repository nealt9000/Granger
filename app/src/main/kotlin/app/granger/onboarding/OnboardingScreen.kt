package app.granger.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.formatBytes

/** What the screen asks of the view model; a plain class so the content can be tested with lambdas. */
class OnboardingActions(
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onRefreshCatalog: () -> Unit = {},
    val onChooseStart: (String?) -> Unit = {},
    val onToggleLine: (String) -> Unit = {},
    val onRegionQuery: (String) -> Unit = {},
    val onChooseRegion: (String?) -> Unit = {},
    val onToggleUseCase: (String) -> Unit = {},
    val onSeeDomain: (String) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onChooseVolume: (String) -> Unit = {},
    val onClearNotice: () -> Unit = {},
)

/** [onDone] leaves the flow (Start or Skip finished); [onSeeDomain] opens the Kiwix screen filtered to a domain. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit, onSeeDomain: (String) -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { if (finished) onDone() }
    BackHandler(onBack = viewModel::back)
    OnboardingContent(
        ui,
        OnboardingActions(
            onNext = viewModel::next, onBack = viewModel::back, onSkip = viewModel::skip, onStart = viewModel::start,
            onRefreshCatalog = viewModel::refreshCatalog, onChooseStart = viewModel::chooseStart, onToggleLine = viewModel::toggleLine,
            onRegionQuery = viewModel::setRegionQuery, onChooseRegion = viewModel::chooseRegion, onToggleUseCase = viewModel::toggleUseCase,
            onSeeDomain = onSeeDomain, onWifiOnly = viewModel::setWifiOnly, onChooseVolume = viewModel::chooseVolume,
            onClearNotice = viewModel::clearNotice,
        ),
    )
}

private val Step.title: String
    get() = when (this) {
        Step.WELCOME -> "Welcome"
        Step.PHONE -> "This phone"
        Step.START -> "Pick a start"
        Step.SITUATION -> "Your situation"
        Step.REVIEW -> "Review and start"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingContent(ui: OnboardingUi, actions: OnboardingActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.step.title) },
                actions = { TextButton(onClick = actions.onSkip) { Text("Skip") } },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (ui.step != Step.WELCOME) OutlinedButton(onClick = actions.onBack) { Text("Back") } else Spacer(Modifier)
                when (ui.step) {
                    Step.WELCOME -> Button(onClick = actions.onNext) { Text("Get started") }
                    Step.REVIEW -> Button(onClick = actions.onStart) { Text(if (ui.review.isEmpty) "Finish" else "Start") }
                    else -> Button(onClick = actions.onNext) { Text("Next") }
                }
            }
        },
        snackbarHost = {
            ui.notice?.let { notice ->
                Snackbar(action = { TextButton(onClick = actions.onClearNotice) { Text("OK") } }) { Text(notice) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("onboarding-list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            when (ui.step) {
                Step.WELCOME -> welcome()
                Step.PHONE -> phone(ui)
                Step.START -> start(ui, actions)
                Step.SITUATION -> situation(ui, actions)
                Step.REVIEW -> review(ui, actions)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.welcome() {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup()
            Spacer(Modifier.height(24.dp))
            Text(
                "A library that works with no signal: encyclopedias, books, maps and manuals, on your phone.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.phone(ui: OnboardingUi) {
    item { Text(ui.phone.tierLine, style = MaterialTheme.typography.bodyLarge) }
    ui.phone.researchLine?.let { line -> item { Text(line, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) } }
    item { Text("Storage", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    items(ui.phone.volumes, key = { it.root.path }) { volume ->
        Text("${volume.label}: ${formatBytes(volume.freeBytes)} free of ${formatBytes(volume.totalBytes)}", Modifier.padding(top = 4.dp))
    }
    if (ui.phone.efficientBytes > 0L) {
        item {
            Text(
                "The Efficient start is ${formatBytes(ui.phone.efficientBytes)}; the Comprehensive library is ${formatBytes(ui.phone.comprehensiveBytes)}. " +
                    "A full Wikipedia alone is about 100 GB.",
                Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.start(ui: OnboardingUi, actions: OnboardingActions) {
    if (ui.noCatalog) {
        item {
            Column {
                Text("Refresh the catalog to see the choices.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = actions.onRefreshCatalog, enabled = !ui.refreshing, modifier = Modifier.padding(top = 12.dp)) {
                    Text(if (ui.refreshing) "Refreshing…" else "Refresh")
                }
            }
        }
        return
    }
    item {
        Column(Modifier.selectableGroup()) {
            for (choice in ui.starts) {
                Choice(
                    title = "${choice.title} · ${formatBytes(choice.bytes(ui.unticked))}",
                    blurb = choice.blurb,
                    selected = choice.id == ui.startId,
                ) { actions.onChooseStart(choice.id) }
            }
            Choice("Neither, I'll choose later", "Home and the Library are yours either way.", selected = ui.startId == null) { actions.onChooseStart(null) }
        }
    }
    val start = ui.start ?: return
    item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
    items(start.lines, key = { it.id }) { line ->
        val ticked = line.id !in ui.unticked
        Row(
            Modifier.fillMaxWidth().toggleable(value = ticked, enabled = !line.installed, role = Role.Checkbox) { actions.onToggleLine(line.id) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = ticked || line.installed, onCheckedChange = null, enabled = !line.installed)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(line.title)
                Text(
                    listOfNotNull(if (line.count > 1) "${line.count} books" else null, formatBytes(line.bytes), if (line.installed) "on the phone" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item {
        val free = ui.chosenVolume?.freeBytes ?: 0L
        val total = start.bytes(ui.unticked)
        Text(
            "${formatBytes(total)} of ${formatBytes(free)} free",
            Modifier.padding(top = 12.dp),
            color = if (total > free) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.situation(ui: OnboardingUi, actions: OnboardingActions) {
    item { Text("Your region", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    item {
        OutlinedTextField(
            value = ui.regionQuery,
            onValueChange = actions.onRegionQuery,
            label = { Text("Country or area") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    ui.region?.let { region ->
        item {
            Text("${region.title} · ${formatBytes(region.bytes)}${if (region.installed) " · on the phone" else ""}", Modifier.padding(top = 8.dp))
        }
        item { TextButton(onClick = { actions.onChooseRegion(null) }) { Text("No region") } }
    }
    val shown = if (ui.regionQuery.isBlank() && ui.region != null) Regions(emptyList(), emptyList()) else ui.regions
    items(shown.countries.take(if (ui.regionQuery.isBlank()) 12 else 40), key = { "c-${it.pack.id}" }) { RegionRow(it, ui, actions) }
    if (shown.larger.isNotEmpty()) item { Text("Larger areas", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
    items(shown.larger, key = { "l-${it.pack.id}" }) { RegionRow(it, ui, actions) }

    item { Text("What you'll use it for", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    items(ui.additions, key = { it.useCase.id }) { addition ->
        val on = addition.useCase.id in ui.useCaseIds
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = on, onClick = { actions.onToggleUseCase(addition.useCase.id) }, label = { Text(addition.useCase.title) })
                Spacer(Modifier.weight(1f))
                OnboardingViewModel.DOMAIN_OF[addition.useCase.id]?.let { domain ->
                    TextButton(onClick = { actions.onSeeDomain(domain) }) { Text("See") }
                }
            }
            Text(
                if (addition.lines.isEmpty()) "${addition.useCase.blurb} Already covered by your choices."
                else "${addition.useCase.blurb} Adds ${formatBytes(addition.bytes)} · ${addition.packs} ${if (addition.packs == 1) "item" else "items"}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RegionRow(region: Region, ui: OnboardingUi, actions: OnboardingActions) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = region.pack.id == ui.region?.pack?.id, role = Role.RadioButton) { actions.onChooseRegion(region.pack.id) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = region.pack.id == ui.region?.pack?.id, onClick = null)
        Text("${region.title} · ${formatBytes(region.bytes)}${if (region.installed) " · on the phone" else ""}", Modifier.padding(start = 8.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.review(ui: OnboardingUi, actions: OnboardingActions) {
    if (ui.review.isEmpty) {
        item { Text("Nothing to download yet. Home and the Library are ready; the Kiwix screen has everything else.", style = MaterialTheme.typography.bodyLarge) }
    }
    for (group in ui.review.groups) {
        item(key = "g-${group.title}") { Text(group.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items(group.lines, key = { "r-${it.id}" }) { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(line.title, Modifier.weight(1f))
                Text(formatBytes(line.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!ui.review.isEmpty) {
        item {
            Text(
                "${formatBytes(ui.review.bytes)} of ${formatBytes(ui.review.freeBytes)} free",
                Modifier.padding(top = 12.dp),
                color = if (ui.review.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Download on Wi-Fi only", Modifier.weight(1f))
                Switch(checked = ui.wifiOnly, onCheckedChange = actions.onWifiOnly)
            }
        }
        if (ui.phone.volumes.size > 1) {
            item { Text("Store on", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
            items(ui.phone.volumes, key = { "v-${it.root.path}" }) { volume ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = volume.root.path == ui.volumePath, role = Role.RadioButton) { actions.onChooseVolume(volume.root.path) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = volume.root.path == ui.volumePath, onClick = null)
                    Text("${volume.label} · ${formatBytes(volume.freeBytes)} free", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Choice(title: String, blurb: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
