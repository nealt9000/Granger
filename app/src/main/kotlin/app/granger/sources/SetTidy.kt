package app.granger.sources

import app.granger.resolve
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// TidyOffer, TidyDismissals and SetTidy are shared since Plan I7 Task 7c (core:screens, SetTidyOffers.kt); the card stays here.

/** The offers Keep has answered, in SharedPreferences `"home"`, beside Home's own marks. */
fun tidyDismissals(prefs: android.content.SharedPreferences) =
    TidyDismissals(prefs.getStringSet(TidyDismissals.KEY, null).orEmpty().toSet()) { prefs.edit().putStringSet(TidyDismissals.KEY, it).apply() }

/** One tidy offer as a card: its sentence, then Keep both and Remove (Plan WF Decision 5). Shared by Home and Downloads. */
@Composable
fun TidyOfferCard(offer: TidyOffer, onRemove: () -> Unit, onKeep: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).testTag("tidy-offer")) {
        Text(offer.text.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onKeep) { Text(offer.keepLabel.resolve()) }
            TextButton(onClick = onRemove) { Text(offer.removeLabel.resolve()) }
        }
    }
}
