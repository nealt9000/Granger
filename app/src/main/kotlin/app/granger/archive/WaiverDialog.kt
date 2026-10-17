package app.granger.archive

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** The "show everything" waiver, shared by the archive.org screen and Settings. */
@Composable
fun WaiverDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show everything on archive.org?") },
        text = {
            Text(
                "Granger is a browser for archive.org. It doesn't host, check or vouch for anything there, " +
                    "and much of it is still under copyright. You're responsible for having the right to download and use what you take.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I accept") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
