package app.granger.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import java.util.Locale

/** Settings → Appearance → Language (Plan L2 Task 3), on [languages]. */
@Composable
fun LanguageSettingsScreen(languages: AppLanguages, onBack: () -> Unit) {
    val chosen by languages.language.collectAsStateWithLifecycle()
    LanguageSettingsContent(chosen, languages.phoneLocale(), languages::set, onBack)
}

/**
 * The Language screen: a list of sections, each an item with its own heading. Today only "App language"; Plan L3 adds
 * "Your library's languages" as a second item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSettingsContent(chosen: AppLanguage, phone: Locale, onChoose: (AppLanguage) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "app") {
                SettingsHeader(stringResource(R.string.settings_language_app))
                Box(Modifier.padding(horizontal = 16.dp)) {
                    RadioGroup(AppLanguage.entries, chosen, { languageLabel(it, phone) }, onChoose)
                }
            }
        }
    }
}

/** A choice as the Language screen and the Settings row name it: each language in itself, Follow the phone in the app's. */
@Composable
fun languageLabel(choice: AppLanguage, phone: Locale): String = when (choice) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system, languageName(phone))
    AppLanguage.ENGLISH -> stringResource(R.string.settings_language_english)
    AppLanguage.SPANISH -> stringResource(R.string.settings_language_spanish)
}

/** A locale's language named in itself, capitalised: "Español", "English". */
fun languageName(locale: Locale): String = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
