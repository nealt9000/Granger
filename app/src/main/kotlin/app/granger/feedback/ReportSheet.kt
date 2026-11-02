package app.granger.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.granger.BuildConfig
import app.granger.R

/**
 * Report this answer: what's wrong, an optional note, and what the email carries, before the mail app opens.
 * [guess] names the reply a best guess; [sources] is how many cited titles go with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    guess: Boolean,
    sources: Int,
    onSend: (reason: AskReport.Reason, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf<AskReport.Reason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("report-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(if (guess) R.string.report_title_guess else R.string.report_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.report_what), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.selectableGroup()) {
                for (r in AskReport.Reason.entries) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == r, role = Role.RadioButton, onClick = { reason = r }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(stringResource(reasonLabel(r)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_NOTE) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.report_note)) },
                minLines = 2,
                maxLines = 5,
            )
            Text(stringResource(R.string.feedback_includes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("• " + stringResource(R.string.report_includes_question), style = MaterialTheme.typography.bodyMedium)
            Text("• " + stringResource(if (guess) R.string.report_includes_guess else R.string.report_includes_answer), style = MaterialTheme.typography.bodyMedium)
            if (sources > 0) Text("• " + pluralStringResource(R.plurals.report_includes_sources, sources, sources), style = MaterialTheme.typography.bodyMedium)
            Text("• " + stringResource(R.string.report_includes_build), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.report_privacy, BuildConfig.FEEDBACK_EMAIL),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { reason?.let { onSend(it, note) } }, enabled = reason != null) { Text(stringResource(R.string.feedback_write_email)) }
            }
        }
    }
}

private const val MAX_NOTE = 2_000

private fun reasonLabel(r: AskReport.Reason) = when (r) {
    AskReport.Reason.WRONG -> R.string.report_reason_wrong
    AskReport.Reason.OFFENSIVE -> R.string.report_reason_offensive
    AskReport.Reason.UNSAFE -> R.string.report_reason_unsafe
    AskReport.Reason.OTHER -> R.string.report_reason_other
}
