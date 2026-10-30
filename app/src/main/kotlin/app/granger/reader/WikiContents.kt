package app.granger.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.granger.ebook.SidePanelInsets

/**
 * The wiki's Contents (Plan U3c Decision 4): the article's sections in a side sheet from the right, as the e-book's
 * Contents is drawn. A tap on one jumps the page to its heading ([onJump]); [sections] is null while they're read.
 */
@Composable
fun WikiContentsPanel(sections: List<ArticleSection>?, onJump: (ArticleSection) -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag(WIKI_CONTENTS)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.windowInsetsPadding(SidePanelInsets)) {
                Text("Contents", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                when {
                    sections == null -> Unit
                    sections.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Text("This page has no sections.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(sections, key = { it.blockId }) { section ->
                            Text(
                                section.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable { onJump(section) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal const val WIKI_CONTENTS = "wiki_contents"
