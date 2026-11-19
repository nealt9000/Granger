package app.granger.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.setValue
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
    val onReadNow: () -> Unit = {},
    val onOutfit: () -> Unit = {},
    val onNotNow: () -> Unit = {},
)

/** [onDone] leaves the flow (Start or Skip finished); [onSeeDomain] opens the Kiwix screen filtered to a domain. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onDone: () -> Unit, onSeeDomain: (String) -> Unit, onReadNow: () -> Unit = onDone) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val readingNow by viewModel.readingNow.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { if (finished) { if (readingNow) onReadNow() else onDone() } }
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
            onReadNow = viewModel::startReadingNow, onOutfit = viewModel::outfit, onNotNow = viewModel::notNow,
        ),
    )
}

private val Step.title: String
    @Composable get() = stringResource(
        when (this) {
            Step.WELCOME -> R.string.onboarding_step_welcome
            Step.OUTFIT -> R.string.onboarding_outfit
            Step.REVIEW -> R.string.onboarding_step_review
        },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingContent(ui: OnboardingUi, actions: OnboardingActions) {
    // The region list opens in place on the Outfit page; picking one folds it back (Plan OB call 2).
    var regionOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val welcomeOnline = ui.step == Step.WELCOME && ui.online
    Scaffold(
        // The region search opens the keyboard: the list and the footer stay above it (Plan U3c walkthrough).
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                // Welcome online is the owl and its choice, no bar title (Plan OB call 1).
                title = { if (!welcomeOnline) Text(ui.step.title) },
                navigationIcon = {
                    if (ui.step == Step.OUTFIT) {
                        androidx.compose.material3.IconButton(onClick = actions.onBack) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    when {
                        welcomeOnline -> Unit   // Start reading now is the skip
                        ui.step != Step.WELCOME && ui.online -> TextButton(onClick = actions.onNotNow, modifier = Modifier.testTag("onboarding-not-now")) { Text(stringResource(R.string.onboarding_not_now)) }
                        else -> TextButton(onClick = actions.onSkip) { Text(stringResource(R.string.onboarding_skip)) }
                    }
                },
            )
        },
        bottomBar = {
            when {
                welcomeOnline -> WelcomeChoice(actions)
                ui.step == Step.OUTFIT -> OutfitFooter(ui, actions)
                else -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (ui.step != Step.WELCOME) OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.common_back)) } else Spacer(Modifier)
                    when (ui.step) {
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
                        else -> Button(onClick = actions.onNext) { Text(stringResource(R.string.onboarding_get_started)) }
                    }
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
                    Step.OUTFIT -> outfit(ui, actions, regionOpen) { regionOpen = it }
                    Step.REVIEW -> review(ui, actions)
                }
            }
        }
    }
}

/** Welcome's choice, pinned at the bottom (Plan OB call 1): Start reading now, Outfit for offline, the privacy line under both. */
@Composable
private fun WelcomeChoice(actions: OnboardingActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = actions.onReadNow, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("choose-read-now")) {
            Text(stringResource(R.string.onboarding_read_now), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = actions.onOutfit, modifier = Modifier.padding(top = 4.dp).testTag("choose-outfit")) {
            Text(stringResource(R.string.onboarding_outfit) + " \u203A", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.onboarding_privacy_line),
            Modifier.padding(top = 8.dp).testTag("privacy-line"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The Outfit page's footer (Plan OB call 2): the total against the space, and Review. */
@Composable
private fun OutfitFooter(ui: OnboardingUi, actions: OnboardingActions) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).testTag("outfit-total")) {
                if (!ui.review.isEmpty) {
                    Text(
                        formatBytes(ui.review.bytes), style = MaterialTheme.typography.titleMedium,
                        color = if (ui.review.fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(R.string.onboarding_of_free, "", formatBytes(ui.review.freeBytes)).trimStart(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = actions.onNext, modifier = Modifier.testTag("outfit-review")) { Text(stringResource(R.string.onboarding_review)) }
        }
    }
}

/**
 * Outfit for offline, one page (Plan OB call 2): the phone's space, a start, the region (folded to one row), what you'll
 * use Granger for. The footer holds the total and Review.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.outfit(ui: OnboardingUi, actions: OnboardingActions, regionOpen: Boolean, setRegionOpen: (Boolean) -> Unit) {
    ui.chosenVolume?.let { volume ->
        item(key = "free") {
            Text(
                if (ui.phone.volumes.size > 1) stringResource(R.string.onboarding_free_on_volume, formatBytes(volume.freeBytes), volume.label)
                else stringResource(R.string.onboarding_free_on_phone, formatBytes(volume.freeBytes)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ui.phone.researchLine?.let { line -> item(key = "research") { Text(line.resolve(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) } }
    item(key = "h-start") { Section(stringResource(R.string.onboarding_section_start)) }
    start(ui, actions)
    if (ui.noCatalog) return
    item(key = "h-region") { Section(stringResource(R.string.onboarding_section_region)) }
    if (!regionOpen) {
        item(key = "region-row") {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val region = ui.region
                    // A suggestion too big to tick by default is still offered, with Add (Pixel 7 walkthrough).
                    val offer = ui.suggestion?.takeIf { region == null && !ui.regionSuggested }
                    val shown = region ?: offer
                    val text = shown?.let { stringResource(R.string.onboarding_title_size, it.title, formatBytes(it.bytes)) } ?: stringResource(R.string.onboarding_region_none_row)
                    Text(if (shown?.installed == true) stringResource(R.string.onboarding_on_the_phone, text) else text, Modifier.weight(1f),
                        color = if (offer != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (offer != null) TextButton(onClick = { actions.onChooseRegion(offer.pack.id) }, modifier = Modifier.testTag("region-add")) { Text(stringResource(R.string.onboarding_region_add)) }
                    TextButton(onClick = { setRegionOpen(true) }, modifier = Modifier.testTag("region-choose")) {
                        Text(stringResource(if (offer != null) R.string.onboarding_region_other else R.string.onboarding_region_choose_another))
                    }
                }
            }
        }
    } else {
        region(ui, actions) { setRegionOpen(false) }
        item(key = "region-done") {
            TextButton(onClick = { setRegionOpen(false) }) { Text(stringResource(R.string.onboarding_region_done)) }
        }
    }
    item(key = "h-for") { Section(stringResource(R.string.onboarding_section_for)) }
    uses(ui, actions)
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), Modifier.padding(top = 24.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    )
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
    if (ui.start == null) return
    val start = ui.start!!
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
}

private fun androidx.compose.foundation.lazy.LazyListScope.region(ui: OnboardingUi, actions: OnboardingActions, picked: () -> Unit = {}) {
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
            TextButton(onClick = { keyboard(); actions.onChooseRegion(null); picked() }) { Text(stringResource(R.string.onboarding_no_region)) }
        }
    }
    val offered = ui.suggestion?.takeIf { ui.region == null && ui.regionQuery.isBlank() }
    offered?.let { suggestion ->
        item { Text(stringResource(R.string.onboarding_region_suggested), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
        item(key = "suggested") { RegionRow(suggestion, ui, actions, picked) }
        item { Text(stringResource(R.string.onboarding_other_countries), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
    }
    val shown = if (ui.regionQuery.isBlank() && ui.region != null) Regions(emptyList(), emptyList()) else ui.regions
    items(shown.countries.take(if (ui.regionQuery.isBlank()) 12 else 40), key = { "c-${it.pack.id}" }) { RegionRow(it, ui, actions, picked) }
    if (shown.larger.isNotEmpty()) item { Text(stringResource(R.string.onboarding_larger_areas), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) }
    items(shown.larger, key = { "l-${it.pack.id}" }) { RegionRow(it, ui, actions, picked) }
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
private fun RegionRow(region: Region, ui: OnboardingUi, actions: OnboardingActions, picked: () -> Unit = {}) {
    val keyboard = closeKeyboard()
    Row(
        Modifier.fillMaxWidth().selectable(selected = region.pack.id == ui.region?.pack?.id, role = Role.RadioButton) {
            keyboard()
            actions.onChooseRegion(region.pack.id)
            picked()
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
