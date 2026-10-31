package app.granger.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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

/** Settings → Appearance → Language (Plan L2 Task 3), on [languages], with the library's languages (Plan L3) on [knowledge]. */
@Composable
fun LanguageSettingsScreen(languages: AppLanguages, knowledge: KnowledgeLanguages, onBack: () -> Unit) {
    val chosen by languages.language.collectAsStateWithLifecycle()
    val library by knowledge.languages.collectAsStateWithLifecycle(emptyList())
    val offered by knowledge.offered.collectAsStateWithLifecycle(emptyList())
    LanguageSettingsContent(chosen, languages.phoneLocale(), languages::set, onBack, LibraryLanguages(library, offered, knowledge::set))
}

/** The Language screen's second section (Plan L3): the knowledge languages [chosen] (main first) of those [offered]. */
internal class LibraryLanguages(val chosen: List<String>, val offered: List<String>, val onChange: (List<String>) -> Unit)

/**
 * The Language screen: a list of sections, each an item with its own heading: "App language", then "Your library's
 * languages" when the catalog has tiers in more than one language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSettingsContent(
    chosen: AppLanguage,
    phone: Locale,
    onChoose: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    library: LibraryLanguages? = null,
) {
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
            if (library != null && library.offered.size > 1) {
                item(key = "library") { LibraryLanguagesSection(library) }
            }
        }
    }
}

/**
 * Each language the catalog has tiers for, named in itself, ticked when it's one of the library's. The first ticked is
 * the main one (Pick a start and Home use it first); another ticked one can be made main. The last one ticked stays.
 */
@Composable
private fun LibraryLanguagesSection(library: LibraryLanguages) {
    Column {
        SettingsHeader(stringResource(R.string.settings_language_library))
        Text(
            stringResource(R.string.settings_language_library_blurb),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (code in library.offered) {
            val on = code in library.chosen
            val main = library.chosen.firstOrNull() == code
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = on, enabled = !(on && library.chosen.size == 1), role = Role.Checkbox) {
                        library.onChange(KnowledgeLanguages.toggle(library.chosen, code))
                    }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null, enabled = !(on && library.chosen.size == 1))
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(languageName(Locale.forLanguageTag(code)))
                    if (main && library.chosen.size > 1) {
                        Text(
                            stringResource(R.string.settings_language_main),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (on && !main) {
                    TextButton(onClick = { library.onChange(KnowledgeLanguages.makeMain(library.chosen, code)) }) {
                        Text(stringResource(R.string.settings_language_make_main))
                    }
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
