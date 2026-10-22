package app.granger.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.granger.R

/**
 * The brand on the Welcome screen (Plan H3, Decision 9): the mark and the name. A placeholder (the launcher icon's foreground layer) until
 * Neal's brand decision; Task 5 swaps the assets here and nowhere else.
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text("Granger", style = MaterialTheme.typography.headlineLarge)
    }
}
