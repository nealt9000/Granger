package app.granger.feedback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.granger.BuildConfig

/**
 * Send feedback (Plan F1 part 2 Decision 2): what the email will carry, shown before the mail app opens, with a switch
 * that leaves the crash reports out. [facts] is null while they're read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(
    facts: FeedbackFacts?,
    onSend: (withCrashes: Boolean) -> Unit,
    onClearCrashes: () -> Unit,
    onDismiss: () -> Unit,
    /** A report's full text, for View. */
    readReport: (CrashReport) -> String = { it.file.readText() },
) {
    var withCrashes by rememberSaveable { mutableStateOf(true) }
    var viewing by remember { mutableStateOf<CrashReport?>(null) }
    viewing?.let { report ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text("${report.kind.label} · ${CrashLog.stamp(report.timestamp)}") },
            text = {
                SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(remember(report) { readReport(report) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("feedback-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Send feedback", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your mail app opens with an email to ${BuildConfig.FEEDBACK_EMAIL}. Say what you tried and what happened, " +
                    "then send it from there. Nothing leaves this phone until you do.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (facts == null) {
                Text("Reading this phone…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text("The email includes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            for (line in FeedbackReport.included(facts, withCrashes)) {
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
            if (facts.crashes.isNotEmpty()) {
                ListItem(
                    modifier = Modifier.toggleable(value = withCrashes, role = Role.Switch, onValueChange = { withCrashes = it }),
                    headlineContent = { Text("Attach crash reports") },
                    supportingContent = { Text("What the app was doing when it closed. No titles or searches.") },
                    trailingContent = { Switch(checked = withCrashes, onCheckedChange = null) },
                )
                for (report in facts.crashes) {
                    ListItem(
                        modifier = Modifier.clickable { viewing = report },
                        headlineContent = { Text("${report.kind.label} · ${CrashLog.stamp(report.timestamp)}") },
                        supportingContent = { Text(report.summary, maxLines = 2) },
                        trailingContent = { Text("View", color = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (facts.crashes.isNotEmpty()) TextButton(onClick = onClearCrashes) { Text("Clear crash reports") }
                Button(onClick = { onSend(withCrashes) }) { Text("Write email") }
            }
        }
    }
}
