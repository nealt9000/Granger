package app.granger.voiceinput

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.speech.Availability
import app.granger.core.speech.RecognizerTags
import app.granger.settings.RadioGroup
import app.granger.settings.SettingsHeader
import java.util.Locale

/**
 * Settings → Voice input (Plan A1): the language the mic listens in (the app's; for English, US or UK), a download
 * when that language isn't installed yet, and "Ask as soon as I stop talking". Left out on phones with no on-device
 * recognizer, where there is no mic either.
 */
@Composable
fun VoiceInputSettingsSection(voiceInput: VoiceInput) {
    val status by voiceInput.status.collectAsStateWithLifecycle()
    val download by voiceInput.download.collectAsStateWithLifecycle()
    val english by voiceInput.prefs.englishTag.collectAsStateWithLifecycle()
    // Back from the phone's settings or a finished download: ask the phone again.
    LifecycleResumeEffect(Unit) {
        voiceInput.refresh()
        onPauseOrDispose {}
    }
    if (!status.phoneCanListen) return
    val appLocale = LocalConfiguration.current.locales[0]
    Column {
        SettingsHeader(stringResource(R.string.voice_input_settings_section))
        val tag = status.tag
        ListItem(
            headlineContent = { Text(stringResource(R.string.voice_input_settings_listens_in)) },
            supportingContent = {
                Text(
                    when {
                        tag == null -> stringResource(R.string.voice_input_settings_no_language)
                        status.availability == Availability.READY -> languageName(tag, appLocale)
                        else -> stringResource(R.string.voice_input_settings_needs_download, languageName(tag, appLocale))
                    },
                )
            },
        )
        if (tag?.startsWith("en") == true) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                RadioGroup(
                    listOf(RecognizerTags.ENGLISH_US, RecognizerTags.ENGLISH_UK), english,
                    { stringResource(if (it == RecognizerTags.ENGLISH_UK) R.string.voice_input_settings_english_uk else R.string.voice_input_settings_english_us) },
                    voiceInput.prefs::setEnglishTag,
                )
            }
        }
        if (tag != null && status.availability == Availability.DOWNLOADABLE) {
            when (val d = download) {
                is VoiceInput.Download.Running -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.voice_input_settings_downloading, languageName(tag, appLocale)))
                    val percent = d.percent
                    if (percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
                else -> ListItem(
                    headlineContent = { Text(stringResource(R.string.voice_input_settings_download, languageName(tag, appLocale))) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when (d) {
                                    VoiceInput.Download.Scheduled -> R.string.voice_input_settings_scheduled
                                    VoiceInput.Download.Failed -> R.string.voice_input_settings_download_failed
                                    else -> R.string.voice_input_settings_download_summary
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = voiceInput::downloadCurrent) { Text(stringResource(R.string.voice_input_settings_download_button)) }
                    },
                )
            }
        }
        Text(
            stringResource(R.string.voice_input_settings_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

/** "English (United Kingdom)" for `en-GB`, in the app's language. */
private fun languageName(tag: String, appLocale: Locale): String =
    Locale.forLanguageTag(tag).getDisplayName(appLocale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(appLocale) else it.toString() }
