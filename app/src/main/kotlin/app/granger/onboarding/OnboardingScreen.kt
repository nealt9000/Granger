package app.granger.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.R
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
    val onToggleAsk: () -> Unit = {},
    val onToggleVoice: () -> Unit = {},
    val onToggleSaveSpace: () -> Unit = {},
    val onToggleAlso: () -> Unit = {},
    val onToggleSecond: () -> Unit = {},
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
            onToggleAsk = viewModel::toggleAsk,
            onToggleVoice = viewModel::toggleVoice,
            onToggleSaveSpace = viewModel::toggleSaveSpace,
            onToggleAlso = viewModel::toggleAlso,
            onToggleSecond = viewModel::toggleSecond,
            onRegionQuery = viewModel::setRegionQuery, onChooseRegion = viewModel::chooseRegion, onToggleUseCase = viewModel::toggleUseCase,
            onSeeDomain = onSeeDomain, onWifiOnly = viewModel::setWifiOnly, onChooseVolume = viewModel::chooseVolume,
            onClearNotice = viewModel::clearNotice,
        ),
    )
}

private val Step.title: String
    @Composable get() = stringResource(
        when (this) {
            Step.WELCOME -> R.string.onboarding_step_welcome
            Step.PHONE -> R.string.onboarding_step_phone
            Step.START -> R.string.onboarding_step_start
            Step.REGION -> R.string.onboarding_your_region
            Step.USES -> R.string.onboarding_uses
            Step.REVIEW -> R.string.onboarding_step_review
        },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingContent(ui: OnboardingUi, actions: OnboardingActions) {
    Scaffold(
        // The region search opens the keyboard: the list and Next stay above it (Plan U3c walkthrough).
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(ui.step.title) },
                actions = { TextButton(onClick = actions.onSkip) { Text(stringResource(R.string.onboarding_skip)) } },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (ui.step != Step.WELCOME) OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.common_back)) } else Spacer(Modifier)
                when (ui.step) {
                    Step.WELCOME -> Button(onClick = actions.onNext) { Text(stringResource(R.string.onboarding_get_started)) }
                    Step.REVIEW -> Button(onClick = actions.onStart, enabled = !ui.starting) {
                        if (ui.starting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (ui.review.isEmpty) R.string.onboarding_finish
                                else if (ui.starting) R.string.onboarding_starting else R.string.onboarding_start,
                            ),
                        )
                    }
                    else -> Button(onClick = actions.onNext) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
        },
        snackbarHost = {
            ui.notice?.let { notice ->
                Snackbar(action = { TextButton(onClick = actions.onClearNotice) { Text(stringResource(R.string.common_ok)) } }) { Text(notice.resolve()) }
            }
        },
    ) { padding ->
        // A scroll position per step, so every step opens at its top (Plan U3a Decision 4).
        key(ui.step) {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("onboarding-list"),
                state = rememberLazyListState(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                when (ui.step) {
                    Step.WELCOME -> welcome(ui, actions)
                    Step.PHONE -> phone(ui)
                    Step.START -> start(ui, actions)
                    Step.REGION -> region(ui, actions)
                    Step.USES -> uses(ui, actions)
                    Step.REVIEW -> review(ui, actions)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.welcome(ui: OnboardingUi, actions: OnboardingActions) {
    item {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup()
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.onboarding_tagline), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                ui.intro.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
    // A phone in two languages the catalog has (Plan L3, Neal's call 4): "Also Spanish?", the language named in the app's.
    ui.alsoLanguage?.let { code ->
        item(key = "also") {
            val appLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val name = java.util.Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
            Box(Modifier.padding(top = 32.dp)) {
                Extra(stringResource(R.string.onboarding_also_language, name), stringResource(R.string.onboarding_also_language_blurb), ui.alsoOn, actions.onToggleAlso)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.phone(ui: OnboardingUi) {
    item { Text(ui.phone.tierLine.resolve(), style = MaterialTheme.typography.bodyLarge) }
    ui.phone.researchLine?.let { line -> item { Text(line.resolve(), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) } }
    item { Text(stringResource(R.string.onboarding_storage), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    items(ui.phone.volumes, key = { it.root.path }) { volume ->
        Text(stringResource(R.string.onboarding_volume_free_of, volume.label, formatBytes(volume.freeBytes), formatBytes(volume.totalBytes)), Modifier.padding(top = 4.dp))
    }
    if (ui.phone.smallestBytes > 0L) {
        item {
            Text(
                stringResource(R.string.onboarding_start_sizes, ui.phone.smallestTitle, formatBytes(ui.phone.smallestBytes), formatBytes(ui.phone.comprehensiveBytes)),
                Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.start(ui: OnboardingUi, actions: OnboardingActions) {
    if (ui.noCatalog) {
        item {
            if (ui.catalogFailed) {
                Column {
                    Text(stringResource(R.string.onboarding_catalog_failed), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = actions.onRefreshCatalog, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.onboarding_try_again)) }
                    Text(
                        stringResource(R.string.onboarding_catalog_failed_next),
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.onboarding_catalog_loading), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }
    item {
        Column(Modifier.selectableGroup()) {
            for (choice in ui.starts) {
                Choice(
                    title = stringResource(R.string.onboarding_title_size, choice.title, formatBytes(choice.bytes(ui.unticked))),
                    blurb = choice.blurb.resolve(),
                    selected = choice.id == ui.startId,
                ) { actions.onChooseStart(choice.id) }
            }
            Choice(stringResource(R.string.onboarding_neither), stringResource(R.string.onboarding_neither_blurb), selected = ui.startId == null) { actions.onChooseStart(null) }
        }
    }
    ui.secondLine?.let { second ->
        item(key = "second") {
            Extra(
                stringResource(R.string.onboarding_title_size, second.title.resolve(), formatBytes(second.bytes)),
                stringResource(R.string.onboarding_second_blurb), ui.secondChosen,
                actions.onToggleSecond,
            )
        }
    }
    ui.askLine?.let { ask ->
        // Offered under every choice, Neither included: Granger Classics alone is enough to ask (Plan U3a Decision 2).
        item(key = "ask") {
            Extra(
                stringResource(R.string.onboarding_title_size, ask.title.resolve(), formatBytes(ask.bytes)),
                stringResource(R.string.onboarding_ask_blurb), ui.askChosen,
                actions.onToggleAsk,
            )
        }
    }
    ui.voiceLine?.let { voice ->
        item(key = "voice") {
            Extra(
                stringResource(R.string.onboarding_title_size, voice.title.resolve(), formatBytes(voice.bytes)),
                stringResource(R.string.onboarding_voice_blurb), ui.voiceChosen,
                actions.onToggleVoice,
            )
        }
    }
    val start = ui.start ?: return
    item(key = "save-space") {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = ui.saveSpace, role = Role.Switch) { actions.onToggleSaveSpace() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_save_space))
                Text(stringResource(R.string.onboarding_save_space_blurb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.saveSpace, onCheckedChange = null)
        }
    }
    item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
    items(start.lines, key = { it.id }) { line ->
        val ticked = line.id !in ui.unticked
        Row(
            Modifier.fillMaxWidth().toggleable(value = ticked, enabled = !line.installed, role = Role.Checkbox) { actions.onToggleLine(line.id) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = ticked || line.installed, onCheckedChange = null, enabled = !line.installed)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(line.title.resolve())
                val size = if (line.count > 1) {
                    stringResource(R.string.onboarding_title_size, pluralStringResource(R.plurals.common_books, line.count, line.count), formatBytes(line.bytes))
                } else {
                    formatBytes(line.bytes)
                }
                Text(
                    if (line.installed) stringResource(R.string.onboarding_on_the_phone, size) else size,
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
            stringResource(R.string.onboarding_of_free, formatBytes(total), formatBytes(free)),
            Modifier.padding(top = 12.dp),
            color = if (total > free) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.region(ui: OnboardingUi, actions: OnboardingActions) {
    item { Text(stringResource(R.string.onboarding_region_intro), style = MaterialTheme.typography.bodyMedium) }
    item {
        val keyboard = closeKeyboard()
        OutlinedTextField(
            value = ui.regionQuery,
            onValueChange = actions.onRegionQuery,
            label = { Text(stringResource(R.string.onboarding_region_search)) },
            singleLine = true,
            // Done puts the keyboard away, so the results and Next show whole.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard() }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    ui.region?.let { region ->
        if (ui.regionSuggested) {
            item { Text(stringResource(R.string.onboarding_region_suggested), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
        }
        item {
            val text = stringResource(R.string.onboarding_title_size, region.title, formatBytes(region.bytes))
            Text(if (region.installed) stringResource(R.string.onboarding_on_the_phone, text) else text, Modifier.padding(top = 8.dp))
        }
        item {
            val keyboard = closeKeyboard()
            TextButton(onClick = { keyboard(); actions.onChooseRegion(null) }) { Text(stringResource(R.string.onboarding_no_region)) }
        }
    }
    val offered = ui.suggestion?.takeIf { ui.region == null && ui.regionQuery.isBlank() }
    offered?.let { suggestion ->
        item { Text(stringResource(R.string.onboarding_region_suggested), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
        item(key = "suggested") { RegionRow(suggestion, ui, actions) }
        item { Text(stringResource(R.string.onboarding_other_countries), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
    }
    val shown = if (ui.regionQuery.isBlank() && ui.region != null) Regions(emptyList(), emptyList()) else ui.regions
    items(shown.countries.take(if (ui.regionQuery.isBlank()) 12 else 40), key = { "c-${it.pack.id}" }) { RegionRow(it, ui, actions) }
    if (shown.larger.isNotEmpty()) item { Text(stringResource(R.string.onboarding_larger_areas), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
    items(shown.larger, key = { "l-${it.pack.id}" }) { RegionRow(it, ui, actions) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.uses(ui: OnboardingUi, actions: OnboardingActions) {
    item { Text(stringResource(R.string.onboarding_uses_intro), style = MaterialTheme.typography.bodyMedium) }
    items(ui.additions, key = { it.useCase.id }) { addition ->
        val on = addition.useCase.id in ui.useCaseIds
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = on, onClick = { actions.onToggleUseCase(addition.useCase.id) }, label = { Text(addition.title) })
                Spacer(Modifier.weight(1f))
                OnboardingViewModel.DOMAIN_OF[addition.useCase.id]?.let { domain ->
                    TextButton(onClick = { actions.onSeeDomain(domain) }) { Text(stringResource(R.string.onboarding_see)) }
                }
            }
            Text(
                if (addition.lines.isEmpty()) stringResource(R.string.onboarding_use_covered, addition.blurb)
                else stringResource(
                    R.string.onboarding_use_adds, addition.blurb, formatBytes(addition.bytes),
                    pluralStringResource(R.plurals.onboarding_items, addition.packs, addition.packs),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Puts the keyboard away and takes focus off the search field. Picking a region calls it, so the choice and Next aren't
 * left under the keyboard (Plan U3c walkthrough).
 */
@Composable
private fun closeKeyboard(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return {
        keyboard?.hide()
        focus.clearFocus()
    }
}

/** One map to pick; picking it closes the keyboard. */
@Composable
private fun RegionRow(region: Region, ui: OnboardingUi, actions: OnboardingActions) {
    val keyboard = closeKeyboard()
    Row(
        Modifier.fillMaxWidth().selectable(selected = region.pack.id == ui.region?.pack?.id, role = Role.RadioButton) {
            keyboard()
            actions.onChooseRegion(region.pack.id)
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = region.pack.id == ui.region?.pack?.id, onClick = null)
        val text = if (region.place != null) {
            stringResource(R.string.onboarding_region_in, region.title, region.pack.title, formatBytes(region.bytes))
        } else {
            stringResource(R.string.onboarding_title_size, region.title, formatBytes(region.bytes))
        }
        Text(if (region.installed) stringResource(R.string.onboarding_on_the_phone, text) else text, Modifier.padding(start = 8.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.review(ui: OnboardingUi, actions: OnboardingActions) {
    if (ui.review.isEmpty) {
        item { Text(stringResource(R.string.onboarding_review_empty), style = MaterialTheme.typography.bodyLarge) }
    }
    for (group in ui.review.groups) {
        item(key = "g-${group.title}") { Text(group.title.resolve(), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items(group.lines, key = { "r-${it.id}" }) { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(line.title.resolve(), Modifier.weight(1f))
                Text(formatBytes(line.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!ui.review.isEmpty) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.onboarding_of_free, formatBytes(ui.review.bytes), formatBytes(ui.review.freeBytes)),
                    color = if (ui.review.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                for (note in ui.review.notes) {
                    Text(note.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OnboardingModel.quickFirstText(ui.review)?.let {
                    Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Home's card counts the files only; say what the difference is (Plan U3a Decision 5).
                if (ui.review.indexBytes > 0L) {
                    Text(
                        stringResource(R.string.onboarding_indexes, formatBytes(ui.review.indexBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.onboarding_wifi_only), Modifier.weight(1f))
                Switch(checked = ui.wifiOnly, onCheckedChange = actions.onWifiOnly)
            }
        }
        if (ui.phone.volumes.size > 1) {
            item { Text(stringResource(R.string.onboarding_store_on), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge) }
            items(ui.phone.volumes, key = { "v-${it.root.path}" }) { volume ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = volume.root.path == ui.volumePath, role = Role.RadioButton) { actions.onChooseVolume(volume.root.path) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = volume.root.path == ui.volumePath, onClick = null)
                    Text(stringResource(R.string.onboarding_volume_free, volume.label, formatBytes(volume.freeBytes)), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** A ticked-by-default extra under the start choices: Ask, the voice. */
@Composable
private fun Extra(title: String, blurb: String, ticked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(value = ticked, role = Role.Checkbox) { onToggle() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(title)
            Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
