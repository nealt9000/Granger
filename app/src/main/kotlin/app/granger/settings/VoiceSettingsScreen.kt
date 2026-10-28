package app.granger.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import app.granger.formatBytes

object VoiceSettingsTags {
    const val VOICE_ROW = "voice_row"
    const val GET = "voice_get"
    const val REMOVE = "voice_remove"
    const val SPEED = "voice_speed"
    const val MARK = "voice_mark"
    const val SPEAK_ANSWERS = "voice_speak_answers"
    const val MORE = "voice_more"
}

/**
 * Settings → Voice (Plan V1): the voice that reads aloud, the catalog's voices to get or remove (with their
 * licence), the speed, and whether the sentence being read is marked on the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(viewModel: VoiceSettingsViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showMore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.notice) {
        val n = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        viewModel.dismissNotice()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "reads-with") { Heading("Reads with") }
            if (ui.voices.isEmpty()) {
                item(key = "none") {
                    ListItem(
                        headlineContent = { Text("No offline voice on this phone") },
                        supportingContent = { Text("Get the Granger voice below, or install an offline voice for the phone's text-to-speech.") },
                    )
                }
            }
            // The Granger voices are listed, and the system voice that reads if it is one; every other system voice
            // (the engine lists dozens) stays behind one row until asked for (Plan U2 Decision 9).
            val (shown, more) = splitVoices(ui.voices, ui.chosen)
            val list = if (showMore) shown + more else shown
            items(list.size, key = { "voice-${list[it].id.encode()}" }) { i ->
                val voice = list[i]
                val chosen = (ui.chosen ?: ui.voices.first().id) == voice.id
                ListItem(
                    headlineContent = { Text(voice.title) },
                    supportingContent = { Text(if (voice.id is VoiceId.Granger) "Granger voice · ${voice.language}" else "System voice · ${voice.language}") },
                    leadingContent = { RadioButton(selected = chosen, onClick = null) },
                    modifier = Modifier.selectable(selected = chosen, role = Role.RadioButton, onClick = { viewModel.choose(voice.id) }).testTag(VoiceSettingsTags.VOICE_ROW),
                )
            }
            if (more.isNotEmpty() && !showMore) {
                item(key = "more-voices") {
                    TextButton(onClick = { showMore = true }, modifier = Modifier.padding(horizontal = 4.dp).testTag(VoiceSettingsTags.MORE)) {
                        Text("System voices (${more.size})")
                    }
                }
            }
            item(key = "catalog") { Heading("Granger voices") }
            if (ui.catalog.isEmpty()) item(key = "no-catalog") { ListItem(headlineContent = { Text("The catalog lists no voices yet.") }) }
            items(ui.catalog.size, key = { "catalog-${ui.catalog[it].entry.id}" }) { i ->
                val row = ui.catalog[i]
                val e = row.entry
                Column {
                    ListItem(
                        headlineContent = { Text(e.title) },
                        supportingContent = {
                            Column {
                                Text("${formatBytes(e.sizeBytes)} download · ${e.license}")
                                if (e.attribution.isNotBlank()) Text(e.attribution, style = MaterialTheme.typography.bodySmall)
                                row.error?.let { Text("Download failed: $it", color = MaterialTheme.colorScheme.error) }
                            }
                        },
                        trailingContent = {
                            when {
                                row.installed -> TextButton(onClick = { viewModel.remove(e) }, modifier = Modifier.testTag(VoiceSettingsTags.REMOVE)) { Text("Remove") }
                                row.progress != null || row.queued -> TextButton(onClick = { viewModel.cancel(e) }) { Text("Cancel") }
                                row.error != null -> TextButton(onClick = { viewModel.retry(e) }) { Text("Retry") }
                                else -> TextButton(onClick = { viewModel.get(e) }, modifier = Modifier.testTag(VoiceSettingsTags.GET)) { Text("Get") }
                            }
                        },
                    )
                    row.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
                    HorizontalDivider()
                }
            }
            item(key = "speed") {
                Heading("Speed")
                var dragging by remember { mutableStateOf<Float?>(null) }
                val shown = dragging ?: ui.speed
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Slider(
                        value = shown,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = { dragging?.let(viewModel::setSpeed); dragging = null },
                        valueRange = SharedVoicePrefs.MIN_SPEED..SharedVoicePrefs.MAX_SPEED,
                        steps = 12,
                        modifier = Modifier.weight(1f).testTag(VoiceSettingsTags.SPEED),
                    )
                    Text("%.1f×".format(shown), Modifier.padding(start = 12.dp, top = 12.dp))
                }
            }
            item(key = "mark") {
                ListItem(
                    headlineContent = { Text("Mark the sentence being read") },
                    trailingContent = { Switch(checked = ui.markSentence, onCheckedChange = viewModel::setMarkSentence, modifier = Modifier.testTag(VoiceSettingsTags.MARK)) },
                )
            }
            item(key = "speak-answers") {
                ListItem(
                    headlineContent = { Text("Speak answers as they arrive") },
                    supportingContent = { Text("Ask reads a new answer aloud while it is written. Any answer can be read from its speaker button.") },
                    trailingContent = { Switch(checked = ui.speakAnswers, onCheckedChange = viewModel::setSpeakAnswers, modifier = Modifier.testTag(VoiceSettingsTags.SPEAK_ANSWERS)) },
                )
            }
        }
    }
}

/**
 * The voices to list first and the ones behind "System voices": every Granger voice, plus the voice in use when it's a
 * system one ([chosen] if it's installed, else the first voice, which is what reads then); every other system voice
 * goes second.
 */
internal fun splitVoices(voices: List<Voice>, chosen: VoiceId?): Pair<List<Voice>, List<Voice>> {
    val inUse = voices.firstOrNull { it.id == chosen } ?: voices.firstOrNull()
    val shown = voices.filter { it.id is VoiceId.Granger || it == inUse }
    return shown to voices.filterNot { it in shown }
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
