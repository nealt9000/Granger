package app.granger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.theme.GrangerColors
import app.granger.theme.Wordmark
import app.granger.theme.MonoLabel

/** Horizontal lockup: mark, "Granger", tagline. Use on Welcome / About. */
@Composable
fun GrangerLockup(modifier: Modifier = Modifier, showTagline: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.ic_granger_mark), contentDescription = null, modifier = Modifier.size(56.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Granger", style = Wordmark.copy(fontSize = 40.sp), color = MaterialTheme.colorScheme.onSurface)
            if (showTagline) Text(
                stringResource(R.string.brand_tagline).uppercase(),
                style = MonoLabel,
                color = GrangerColors.Tertiary,
            )
        }
    }
}
