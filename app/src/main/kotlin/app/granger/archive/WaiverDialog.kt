package app.granger.archive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.granger.R

/** The "show everything" waiver, shared by the archive.org screen and Settings. */
@Composable
fun WaiverDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_waiver_title)) },
        text = {
            Text(stringResource(R.string.archive_waiver_body))
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.archive_waiver_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
