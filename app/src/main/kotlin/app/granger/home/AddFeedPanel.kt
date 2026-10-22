package app.granger.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.granger.core.library.FeedKind

/** What a feed kind is called on Home. */
val FeedKind.label: String
    get() = when (this) {
        FeedKind.RANDOM -> "Random"
        FeedKind.ON_THIS_DAY -> "On this day"
        FeedKind.SEARCH -> "Search"
    }

/** Adding a feed is a scope, a kind and (for Search) a query: no per-widget code (spec §5). Sits at the top of Home. */
@Composable
fun AddFeedPanel(choices: List<ScopeChoice>, onAdd: (FeedScope, FeedKind, String) -> Unit, onCancel: () -> Unit) {
    var scopeIndex by rememberSaveable { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf(FeedKind.RANDOM) }
    var query by rememberSaveable { mutableStateOf("") }
    val canAdd = scopeIndex in choices.indices && (kind != FeedKind.SEARCH || query.isNotBlank())
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text("Add feed", style = MaterialTheme.typography.titleMedium)
        Text("From", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.selectableGroup()) {
            choices.forEachIndexed { i, choice -> Choice(choice.label, i == scopeIndex) { scopeIndex = i } }
        }
        Text("Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.selectableGroup()) {
            for (k in FeedKind.entries) Choice(k.label, k == kind) { kind = k }
        }
        if (kind == FeedKind.SEARCH) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search for") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(enabled = canAdd, onClick = { onAdd(choices[scopeIndex].scope, kind, query.trim()) }) { Text("Add") }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
