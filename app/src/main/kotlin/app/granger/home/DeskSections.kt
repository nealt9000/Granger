package app.granger.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.explore.Eyebrow
import app.granger.explore.SpreadColors
import app.granger.explore.SpreadType
import app.granger.online.ReadyOfflineUi
import app.granger.shell.RailEntry

/**
 * Continue as your desk opens with it (Plan O3 Task 8; Neal 2026-11-19, direction A in A + B): the article last read as a
 * lead plate, its picture large under a display title, then the rest as a short list, each with its mark.
 */
@Composable
internal fun ContinueDesk(entries: List<RailEntry>, onOpenTab: (Long) -> Unit, onPlay: (RailEntry.Play) -> Unit) {
    if (entries.isEmpty()) return
    // The lead is the last article (or book) read: plays, which have no picture of their own, go in the list (direction A).
    val lead = entries.firstOrNull { it is RailEntry.Tab } ?: entries.first()
    val rest = entries.filter { it !== lead }
    Column(Modifier.testTag(DeskTags.CONTINUE)) {
        Eyebrow(stringResource(R.string.home_continue), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp))
        when (lead) {
            is RailEntry.Tab -> LeadPlate(lead) { onOpenTab(lead.tab.tabId) }
            is RailEntry.Play -> ContinueRow(lead.title, stringResource(lead.kindRes), null, null) { onPlay(lead) }
            else -> {}
        }
        for (entry in rest.take(REST)) when (entry) {
            is RailEntry.Tab -> ContinueRow(entry.tab.title, entry.tab.subtitle.resolve(), entry.tab.packId.takeIf { entry.tab.path.isNotEmpty() }, entry.tab.path) { onOpenTab(entry.tab.tabId) }
            is RailEntry.Play -> ContinueRow(entry.title, stringResource(entry.kindRes), null, null) { onPlay(entry) }
            else -> {}
        }
    }
}

@Composable
private fun LeadPlate(entry: RailEntry.Tab, onOpen: () -> Unit) {
    val tab = entry.tab
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = tab.title }.testTag(DeskTags.LEAD)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cover = app.granger.shell.rememberRailCover(tab.coverPath, maxWidth)
            if (cover != null) {
                Box {
                    Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(LEAD_RATIO))
                    if (tab.path.isNotEmpty() && tab.packId.isNotEmpty()) {
                        app.granger.online.AvailabilityMark(tab.packId, tab.path, Modifier.align(Alignment.TopEnd).padding(10.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            Text(tab.title, style = SpreadType.display.copy(fontSize = 30.sp, lineHeight = 34.sp), color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(tab.subtitle.resolve(), style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ContinueRow(title: String, subtitle: String, packId: String?, path: String?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp).testTag(DeskTags.ROW),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SpreadType.serif.copy(fontSize = 18.sp, lineHeight = 23.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (packId != null && path != null) app.granger.online.AvailabilityMark(packId, path)
    }
}

/** What Ready offline's suggestions do. */
class ReadyActions(
    val onKeepTheRead: (List<Pair<String, String>>) -> Unit = {},
    val onGetPack: (String) -> Unit = {},
    /** The whole picture: today's Readiness screen, Outfitting from O4. */
    val onSeeAll: () -> Unit = {},
)

/**
 * Ready offline (Plan O3 Task 8; direction B in A + B): your recent reads and how many work offline, a bar for each subject
 * split into on this phone / saved for now / online, then what would fill the gaps.
 */
@Composable
fun ReadyOfflineSection(ui: ReadyOfflineUi, actions: ReadyActions, seeAll: Boolean = true) {
    Column(Modifier.padding(horizontal = 16.dp).testTag(DeskTags.READY), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(pluralStringResource(R.plurals.home_ready_eyebrow, ui.recent, ui.recent))
        Text(
            pluralStringResource(R.plurals.home_ready_headline, ui.recent, ui.offline, ui.recent),
            style = SpreadType.display.copy(fontSize = 24.sp, lineHeight = 29.sp), color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Legend()
        for (bar in ui.subjects) SubjectBarRow(bar)
        if (ui.keepTheRead.isNotEmpty() || ui.pack != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.keepTheRead.isNotEmpty()) OutlinedButton(onClick = { actions.onKeepTheRead(ui.keepTheRead) }, shape = RoundedCornerShape(2.dp), modifier = Modifier.testTag(DeskTags.KEEP_READ)) {
                    Text(pluralStringResource(R.plurals.home_ready_keep, ui.keepTheRead.size, ui.keepTheRead.size, app.granger.formatBytes(ui.keepBytes)), style = SpreadType.uiMedium)
                }
            }
            ui.pack?.let { pack ->
                OutlinedButton(onClick = { actions.onGetPack(pack.id) }, shape = RoundedCornerShape(2.dp), modifier = Modifier.testTag(DeskTags.PACK)) {
                    Text(pluralStringResource(R.plurals.home_ready_pack, ui.packCovers, pack.title, app.granger.formatBytes(pack.sizeBytes), ui.packCovers), style = SpreadType.uiMedium)
                }
            }
        }
        if (seeAll) TextButton(onClick = actions.onSeeAll, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(R.string.home_ready_see_all), color = SpreadColors.accent, style = SpreadType.uiMedium)
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(SpreadColors.enamel, stringResource(R.string.mark_on_phone))
        LegendItem(SpreadColors.accent, stringResource(R.string.mark_saved))
        LegendItem(MaterialTheme.colorScheme.outlineVariant, stringResource(R.string.home_ready_online))
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.width(10.dp).height(10.dp).background(color, RoundedCornerShape(1.dp)))
        Text(label, style = SpreadType.ui.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubjectBarRow(bar: app.granger.online.SubjectBar) {
    val name = app.granger.explore.Subject.entries.firstOrNull { it.id == bar.subjectId }?.let { stringResource(it.label) } ?: bar.subjectId
    val words = stringResource(R.string.home_ready_bar, name, bar.onPhone, bar.saved, bar.online)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = words }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = SpreadType.uiMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("${bar.onPhone + bar.saved} / ${bar.total}", style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(1.dp))) {
            if (bar.onPhone > 0) Box(Modifier.weight(bar.onPhone.toFloat()).height(8.dp).background(SpreadColors.enamel))
            if (bar.saved > 0) Box(Modifier.weight(bar.saved.toFloat()).height(8.dp).background(SpreadColors.accent))
            if (bar.online > 0) Box(Modifier.weight(bar.online.toFloat()).height(8.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

object DeskTags {
    const val CONTINUE = "desk_continue"
    const val LEAD = "desk_lead"
    const val ROW = "desk_row"
    const val READY = "desk_ready"
    const val KEEP_READ = "desk_keep_read"
    const val PACK = "desk_pack"
}

/** Continue's list under the lead plate, at most. */
private const val REST = 4
private const val LEAD_RATIO = 16f / 9f
