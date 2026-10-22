package app.granger.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.BuildConfig
import app.granger.R
import app.granger.formatBytes

/**
 * Models, Readiness, Appearance (the toolbar's side), storage and download choices, Music & audiobooks, and About. [scrollToMedia] opens it at the
 * Music & audiobooks section (the Library's link). [onPermissionChecked] runs after each permission check (resume, or
 * an answer to the prompt), so the app can rescan when the permission changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onModels: () -> Unit,
    onReadiness: () -> Unit,
    onBack: () -> Unit,
    scrollToMedia: Boolean = false,
    onPermissionChecked: () -> Unit = {},
    /** Settings → Voice (Plan V1); null leaves the row out. */
    onVoice: (() -> Unit)? = null,
    voiceSummary: String = "",
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val railSide by viewModel.railSide.collectAsStateWithLifecycle()
    val openTo by viewModel.openTo.collectAsStateWithLifecycle()
    val media = viewModel.media
    val listState = rememberLazyListState()
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(scrollToMedia, media) {
        if (scrollToMedia && media != null && !scrolled) {
            scrolled = true
            listState.scrollToItem(settingsIndexOf(MEDIA_SECTION_KEY))
        }
    }
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
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val mediaControls = media?.let { rememberMediaControls(it, onPermissionChecked) }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState) {
            // The rows above Music & audiobooks come from LEADING_KEYS, so the media section's index is computed from them.
            for (key in LEADING_KEYS) item(key = key) {
                when (key) {
                    "models" -> {
                        ListItem(
                            headlineContent = { Text("Models") },
                            supportingContent = { Text("AI models for this phone") },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.selectable(selected = false, onClick = onModels),
                        )
                        HorizontalDivider()
                    }
                    "voice" -> if (onVoice != null) {
                        ListItem(
                            headlineContent = { Text("Voice") },
                            supportingContent = { Text(voiceSummary.ifBlank { "Read aloud" }) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.selectable(selected = false, onClick = onVoice),
                        )
                        HorizontalDivider()
                    }
                    "readiness" -> {
                        ListItem(
                            headlineContent = { Text("Readiness") },
                            supportingContent = { Text("What you hold offline, and whether it still works") },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.selectable(selected = false, onClick = onReadiness),
                        )
                        HorizontalDivider()
                    }
                    "appearance" -> {
                        AppearanceSection(railSide, viewModel::setRailSide, openTo, viewModel::setOpenTo)
                        HorizontalDivider()
                    }
                    "storage" -> StorageSection(ui, viewModel::selectVolume)
                    "wifi" -> {
                        ListItem(
                            headlineContent = { Text("Download on Wi-Fi only") },
                            supportingContent = { Text("Turn off to use mobile data") },
                            trailingContent = { Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                        )
                        HorizontalDivider()
                    }
                    "archive" -> {
                        ListItem(
                            headlineContent = { Text("Archive.org: show everything") },
                            supportingContent = {
                                Text(
                                    if (ui.archiveEverything) "On: everything archive.org serves openly. Turning it off keeps downloaded files."
                                    else "Only public-domain texts, published 1930 or earlier, are shown. Downloaded files stay. Turning it on asks you to accept responsibility.",
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = ui.archiveEverything,
                                    onCheckedChange = { on -> if (on) showWaiver = true else viewModel.withdrawArchiveWaiver() },
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
            if (media != null && mediaControls != null) {
                mediaSettingsSection(
                    mediaControls.ui,
                    onRequestPermission = mediaControls.permission::request,
                    onOpenAppSettings = mediaControls.permission::openAppSettings,
                    onAddFolder = mediaControls.addFolder,
                    onRemoveFolder = media::removeFolder,
                    onRescan = media::rescan,
                    onShowInOtherApps = media::setShowInOtherApps,
                    onReplayGain = media::setReplayGain,
                    onSkipBack = media::setSkipBack,
                    onSkipForward = media::setSkipForward,
                    onHeadsetAutoplay = media::setHeadsetAutoplay,
                )
            }
            item(key = "about") { About() }
        }
    }
}

/** The keys of the rows above Music & audiobooks, in order. Add a row above it here and the scroll follows. */
private val LEADING_KEYS = listOf("models", "voice", "readiness", "appearance", "storage", "wifi", "archive")

/** The list index of [key] when the media section is shown: one item per leading key, then [MEDIA_SECTION_KEY]. */
internal fun settingsIndexOf(key: String): Int = (LEADING_KEYS + MEDIA_SECTION_KEY).indexOf(key)

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

/** "Toolbar side": Left or Right, applied at once (the shell reads the same preference). */
@Composable
internal fun AppearanceSection(
    side: RailSide,
    onSide: (RailSide) -> Unit,
    openTo: OpenTo = OpenTo.RESUME,
    onOpenTo: (OpenTo) -> Unit = {},
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("Toolbar side", modifier = Modifier.padding(top = 8.dp))
        RadioGroup(RailSide.entries, side, { it.label }, onSide)
        Text("Open to", modifier = Modifier.padding(top = 8.dp))
        RadioGroup(OpenTo.entries, openTo, { it.label }, onOpenTo)
    }
}

@Composable
private fun <T> RadioGroup(choices: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
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

private val RailSide.label get() = when (this) {
    RailSide.LEFT -> "Left"
    RailSide.RIGHT -> "Right"
}

private val OpenTo.label get() = when (this) {
    OpenTo.RESUME -> "Where I left off"
    OpenTo.HOME -> "Home"
}

@Composable
private fun StorageSection(ui: SettingsUi, onSelect: (String) -> Unit) {
    val choosable = ui.volumes.size > 1
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage & downloads", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
                        buildString {
                            append("${formatBytes(volume.freeBytes)} free · Granger uses ${formatBytes(volume.grangerBytes)}")
                            if (volume.queuedBytes > 0) append(" · ${formatBytes(volume.queuedBytes)} to download")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (choosable) Text("New downloads go to the selected storage.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Uninstalling Granger deletes everything it downloaded.",
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
        Image(painterResource(R.drawable.granger_mark), contentDescription = null, modifier = Modifier.size(56.dp))
        Text("GRANGER", style = MaterialTheme.typography.titleMedium)
        Text("Civilization, backed up.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "GPLv3 · source on GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
