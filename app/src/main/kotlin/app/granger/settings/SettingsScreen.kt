package app.granger.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import app.granger.Features
import app.granger.media.MediaPermissionState
import app.granger.media.rememberMediaPermission
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import app.granger.archive.WaiverDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.BuildConfig
import app.granger.R
import app.granger.onboarding.BrandLockup
import app.granger.formatBytes

/**
 * Settings in five sections (Plan U3b Decision 9): Library & storage, Reading & listening, Ask, Appearance and About.
 * Voice and Music & audiobooks are their own screens; a null [onVoice] or [onMediaSettings] leaves its row out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onModels: () -> Unit,
    /** Runs the onboarding flow again (Plan H3). */
    onSetUpAgain: () -> Unit = {},
    onBack: () -> Unit,
    /** Settings → Voice (Plan V1). */
    onVoice: (() -> Unit)? = null,
    voiceSummary: String = "",
    /** Settings → Music & audiobooks. */
    onMediaSettings: (() -> Unit)? = null,
    /** The phone holds an audiobook: the row reads "Music & audiobooks", else "Music" (Plan U2 Decision 7). */
    audiobooks: Boolean = false,
    /** Settings → Appearance → Language (Plan L2 Task 3); null leaves the row out. */
    onLanguage: (() -> Unit)? = null,
    /** The language row's summary: the current choice, as the Language screen names it. */
    languageSummary: String = "",
    /** Settings → About → Send feedback (Plan F1 part 2); null leaves the row out. */
    onFeedback: (() -> Unit)? = null,
    /** Crash reports kept on this phone, for the row's summary. */
    crashReports: Int = 0,
    /** Settings → Voice input (Plan A1); null leaves the section out. */
    voiceInput: app.granger.voiceinput.VoiceInput? = null,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val railSide by viewModel.railSide.collectAsStateWithLifecycle()
    val openTo by viewModel.openTo.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    var showWaiver by rememberSaveable { mutableStateOf(false) }
    if (showWaiver) {
        WaiverDialog(
            onAccept = {
                viewModel.acceptArchiveWaiver()
                showWaiver = false
            },
            onDismiss = { showWaiver = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "library") {
                SettingsHeader(stringResource(R.string.settings_section_library))
                StorageSection(ui, viewModel::selectVolume)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
                    supportingContent = { Text(stringResource(R.string.settings_wifi_only_summary)) },
                    trailingContent = { Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_ask_large)) },
                    supportingContent = { Text(stringResource(R.string.settings_ask_large_summary)) },
                    trailingContent = { Switch(checked = ui.askBeforeLarge, onCheckedChange = viewModel::setAskBeforeLarge) },
                )
                if (Features.ARCHIVE_WAIVER) ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_archive_everything)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (ui.archiveEverything) R.string.settings_archive_everything_on else R.string.settings_archive_everything_off,
                            ),
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = ui.archiveEverything,
                            onCheckedChange = { on -> if (on) showWaiver = true else viewModel.withdrawArchiveWaiver() },
                        )
                    },
                )
                LinkRow(stringResource(R.string.settings_set_up_again), stringResource(R.string.settings_set_up_again_summary), onSetUpAgain)
                HorizontalDivider()
            }
            if (onVoice != null || onMediaSettings != null) item(key = "listening") {
                SettingsHeader(stringResource(R.string.settings_section_listening))
                if (onVoice != null) LinkRow(stringResource(R.string.settings_voice), voiceSummary.ifBlank { stringResource(R.string.settings_voice_summary_default) }, onVoice)
                if (onMediaSettings != null) LinkRow(mediaSettingsTitle(audiobooks), stringResource(R.string.settings_media_summary), onMediaSettings)
                HorizontalDivider()
            }
            item(key = "ask") {
                // While Ask rests the Models row (the voices, the embedder) stands without Ask's heading (Plan SQ).
                if (app.granger.Features.RESEARCH) SettingsHeader(stringResource(R.string.settings_section_ask))
                LinkRow(stringResource(R.string.settings_models), stringResource(R.string.settings_models_summary), onModels)
                HorizontalDivider()
            }
            if (voiceInput != null) item(key = "voice_input") {
                app.granger.voiceinput.VoiceInputSettingsSection(voiceInput)
            }
            item(key = "appearance") {
                AppearanceSection(railSide, viewModel::setRailSide, openTo, viewModel::setOpenTo, theme, viewModel::setTheme)
                if (onLanguage != null) LinkRow(stringResource(R.string.settings_language), languageSummary, onLanguage)
                HorizontalDivider()
            }
            item(key = "about") {
                SettingsHeader(stringResource(R.string.settings_section_about))
                if (onFeedback != null) {
                    LinkRow(
                        stringResource(R.string.settings_feedback),
                        if (crashReports == 0) {
                            stringResource(R.string.settings_feedback_summary)
                        } else {
                            pluralStringResource(R.plurals.settings_feedback_summary_crashes, crashReports, crashReports)
                        },
                        onFeedback,
                    )
                }
                About()
            }
        }
    }
}

/**
 * Settings → Music & audiobooks: [mediaSettingsSection] on its own screen (Plan U3b Decision 9), from Settings and
 * the empty Music and Audiobooks screens. [onPermissionChecked] runs after each permission check (resume, or an
 * answer to the prompt), so the app can rescan when the permission changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onPermissionChecked: () -> Unit = {}) {
    val media = viewModel.media
    val controls = media?.let { rememberMediaControls(it, onPermissionChecked) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mediaSettingsTitle(controls?.ui?.audiobooks == true)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (media != null && controls != null) {
                mediaSettingsSection(
                    controls.ui,
                    onRequestPermission = controls.permission::request,
                    onOpenAppSettings = controls.permission::openAppSettings,
                    onAddFolder = controls.addFolder,
                    onRemoveFolder = media::removeFolder,
                    onRescan = media::rescan,
                    onShowInOtherApps = media::setShowInOtherApps,
                    onReplayGain = media::setReplayGain,
                    onSkipBack = media::setSkipBack,
                    onSkipForward = media::setSkipForward,
                    onHeadsetAutoplay = media::setHeadsetAutoplay,
                )
            }
        }
    }
}

/** Settings' Music row and screen: audiobooks are named only once there's one on the phone (Plan U2 Decision 7). */
@Composable
internal fun mediaSettingsTitle(audiobooks: Boolean): String =
    stringResource(if (audiobooks) R.string.settings_music_and_audiobooks else R.string.settings_music)

/** A section's heading, as Appearance and Music & audiobooks have always drawn theirs. */
@Composable
internal fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A row that opens its own screen. */
@Composable
private fun LinkRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.selectable(selected = false, onClick = onClick),
    )
}

private class MediaControls(val ui: MediaSettingsUi, val permission: MediaPermissionState, val addFolder: (Boolean) -> Unit)

/** The permission prompt and the folder picker (read-only grants), wired to [media]. */
@Composable
private fun rememberMediaControls(media: MediaSettings, onPermissionChecked: () -> Unit): MediaControls {
    val mediaUi by media.ui.collectAsStateWithLifecycle()
    val permission = rememberMediaPermission { status ->
        media.setPermission(status)
        onPermissionChecked()
    }
    // Which kind the open picker is adding; saved, since the picker can outlive the activity.
    var pickingAudiobooks by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) media.addFolder(uri.toString(), pickingAudiobooks)
    }
    return MediaControls(mediaUi, permission) { audiobooks ->
        pickingAudiobooks = audiobooks
        picker.launch(null)
    }
}

/** "Theme", "Toolbar side" and "Open to", each applied at once (the activity and the shell read the same preferences). */
@Composable
internal fun AppearanceSection(
    side: RailSide,
    onSide: (RailSide) -> Unit,
    openTo: OpenTo = OpenTo.RESUME,
    onOpenTo: (OpenTo) -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.settings_theme), modifier = Modifier.padding(top = 8.dp))
        RadioGroup(ThemeChoice.entries, theme, { it.label }, onTheme)
        Text(stringResource(R.string.settings_toolbar_side), modifier = Modifier.padding(top = 8.dp))
        RadioGroup(RailSide.entries, side, { it.label }, onSide)
        Text(stringResource(R.string.settings_open_to), modifier = Modifier.padding(top = 8.dp))
        RadioGroup(OpenTo.entries, openTo, { it.label }, onOpenTo)
    }
}

@Composable
internal fun <T> RadioGroup(choices: List<T>, chosen: T, label: @Composable (T) -> String, onChoose: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for (choice in choices) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = choice == chosen, role = Role.RadioButton, onClick = { onChoose(choice) })
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = choice == chosen, onClick = null)
                Text(label(choice), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private val RailSide.label @Composable get() = stringResource(
    when (this) {
        RailSide.LEFT -> R.string.settings_side_left
        RailSide.RIGHT -> R.string.settings_side_right
    },
)

private val ThemeChoice.label @Composable get() = stringResource(
    when (this) {
        ThemeChoice.SYSTEM -> R.string.settings_theme_system
        ThemeChoice.FIELD -> R.string.settings_theme_field
        ThemeChoice.NIGHT -> R.string.settings_theme_night
    },
)

private val OpenTo.label @Composable get() = stringResource(
    when (this) {
        OpenTo.RESUME -> R.string.settings_open_to_resume
        OpenTo.HOME -> R.string.settings_open_to_home
    },
)

@Composable
private fun StorageSection(ui: SettingsUi, onSelect: (String) -> Unit) {
    val choosable = ui.volumes.size > 1
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (volume in ui.volumes) {
            val selected = volume.path == ui.selectedVolume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().let {
                    if (choosable) it.selectable(selected = selected, onClick = { onSelect(volume.path) }) else it
                },
            ) {
                if (choosable) RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = if (choosable) 8.dp else 0.dp)) {
                    Text(volume.label)
                    val used = if (volume.totalBytes > 0) 1f - volume.freeBytes.toFloat() / volume.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { used.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        if (volume.queuedBytes > 0) {
                            stringResource(
                                R.string.settings_storage_usage_queued,
                                formatBytes(volume.freeBytes), formatBytes(volume.grangerBytes), formatBytes(volume.queuedBytes),
                            )
                        } else {
                            stringResource(R.string.settings_storage_usage, formatBytes(volume.freeBytes), formatBytes(volume.grangerBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (choosable) Text(stringResource(R.string.settings_storage_selected), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.settings_storage_uninstall),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun About() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BrandLockup(markWidth = 64.dp)
        Text(stringResource(R.string.settings_about_tagline), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.settings_about_motto),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_about_license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
