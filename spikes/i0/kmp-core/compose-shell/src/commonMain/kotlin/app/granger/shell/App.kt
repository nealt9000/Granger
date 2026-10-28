package app.granger.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.shell.res.Res
import app.granger.shell.res.spectral_medium
import app.granger.shell.res.spectral_regular
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.Font

/** I0 Task 5 (B): one Compose Multiplatform screen pair on Field colours, the same content as the SwiftUI shell. */
private val Field = lightColorScheme(
    primary = Color(0xFF2E4A45), onPrimary = Color(0xFFF3EEE4),
    background = Color(0xFFF3EEE4), onBackground = Color(0xFF1F1813),
    surface = Color(0xFFF3EEE4), onSurface = Color(0xFF1F1813),
    surfaceVariant = Color(0xFFE1D9C9), onSurfaceVariant = Color(0xFF4A453F),
    tertiary = Color(0xFF8A4F1C), outline = Color(0xFF77706A),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(core: app.granger.shell.CoreDemo, args: List<String> = emptyList(), webView: @Composable (String, Modifier) -> Unit) {
    val spectral = FontFamily(Font(Res.font.spectral_regular), Font(Res.font.spectral_medium, FontWeight.Medium))
    MaterialTheme(colorScheme = Field) {
        var reading by remember { mutableStateOf("-openReader" in args) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (reading) "Kurt Gödel" else "GRANGER", fontFamily = spectral, fontWeight = FontWeight.Medium, letterSpacing = if (reading) 0.sp else 3.sp) },
                    navigationIcon = { if (reading) TextButton(onClick = { reading = false }) { Text("‹ Library") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Field.background),
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (reading) webView(core.sampleHtml, Modifier.fillMaxSize()) else Library(core, spectral, "-autoSearch" in args) { reading = true }
            }
        }
    }
}

@Composable
private fun Library(core: app.granger.shell.CoreDemo, spectral: FontFamily, autoSearch: Boolean, open: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("incompleteness") }
    var result by remember { mutableStateOf("") }
    // I0: the screenshot runs start a search from a launch argument, since simctl can't tap.
    LaunchedEffect(Unit) { if (autoSearch) result = core.search(query) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Library", fontFamily = spectral, fontSize = 28.sp)
        }
        items(listOf("Wikipedia (Top 1M)" to "Kiwix · 17.7 GB", "Wiktionary" to "Kiwix · 6.1 GB", "Granger Classics" to "347 books")) { (t, s) ->
            Card(Modifier.fillMaxWidth().clickable(onClick = open), colors = CardDefaults.cardColors(containerColor = Field.surfaceVariant)) {
                Column(Modifier.padding(14.dp)) {
                    Text(t, fontFamily = spectral, fontSize = 18.sp)
                    Text(s, style = MaterialTheme.typography.bodySmall, color = Field.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search by meaning (shared core)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { scope.launch { result = "Searching…"; result = core.search(query) } }) { Text("Search") }
        }
        item {
            SelectionContainer {
                Text(
                    result.ifEmpty { core.lifespan("Kurt Gödel (April 28, 1906 – January 14, 1978) was a logician.") ?: "" } + "\n\n" +
                        core.blocks(core.sampleHtml).take(3).joinToString("\n"),
                    Modifier.fillMaxWidth().background(Field.surfaceVariant).padding(12.dp),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
