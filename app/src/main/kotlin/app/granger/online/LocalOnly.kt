package app.granger.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.granger.R
import kotlinx.coroutines.flow.StateFlow

/** What the Local only switch and banner work on (Plan O3 Tasks 2–3): reach, and turning Local only on or off. */
interface ReachControl {
    val state: StateFlow<ReachState>
    fun setLocalOnly(on: Boolean)
}

/** The app's reach control, provided at the top; null (release before O5, tests) leaves the switch and banner out. */
val LocalReach = staticCompositionLocalOf<ReachControl?> { null }

/**
 * The top bar's Local only switch (builder's call 2): a cloud while online, a struck-out cloud while Local only.
 * TalkBack reads it as a switch, on or off. Not drawn without a [LocalReach].
 */
@Composable
fun LocalOnlyButton(modifier: Modifier = Modifier) {
    val control = LocalReach.current ?: return
    val state by control.state.collectAsState()
    val on = state == ReachState.LOCAL_ONLY || state == ReachState.NEVER
    val label = stringResource(R.string.local_only)
    val now = stringResource(if (on) R.string.local_only_on else R.string.local_only_off)
    IconButton(
        onClick = { control.setLocalOnly(!on) }, enabled = state != ReachState.NEVER,
        modifier = modifier.testTag(LocalOnlyTags.SWITCH).semantics { contentDescription = label; stateDescription = now; role = Role.Switch },
    ) {
        Icon(
            painterResource(if (on) R.drawable.ic_cloud_off else R.drawable.ic_cloud), contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The banner while the internet can't be reached (Plan O3 Task 3; builder's call 1): "Local only. Showing what this phone
 * holds." with Turn off, or "No connection. Showing what this phone holds." Nothing while online.
 */
@Composable
fun ReachBanner(modifier: Modifier = Modifier) {
    val control = LocalReach.current ?: return
    val state by control.state.collectAsState()
    if (state.online) return
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 4.dp).testTag(LocalOnlyTags.BANNER),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(if (state == ReachState.NO_SIGNAL) R.string.reach_banner_no_signal else R.string.reach_banner_local_only),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f),
        )
        if (state == ReachState.LOCAL_ONLY) {
            TextButton(onClick = { control.setLocalOnly(false) }, modifier = Modifier.testTag(LocalOnlyTags.TURN_OFF)) { Text(stringResource(R.string.reach_banner_turn_off)) }
        }
    }
}

object LocalOnlyTags {
    const val SWITCH = "local_only_switch"
    const val BANNER = "reach_banner"
    const val TURN_OFF = "reach_banner_turn_off"
    const val SEARCH_LINE = "search_online_when_connected"
}

/** Search's line while offline (spec §7, Plan O3 Task 4): the phone's results show; online ones come with a connection. */
@Composable
fun OfflineSearchLine(modifier: Modifier = Modifier) {
    val control = LocalReach.current ?: return
    val state by control.state.collectAsState()
    if (state.online) return
    Text(
        stringResource(R.string.search_online_when_connected), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(LocalOnlyTags.SEARCH_LINE),
    )
}

/** Whether the internet can be reached now, for screens that hide what needs it (Get books' Read). True without [LocalReach]. */
@Composable
fun isOnlineNow(): Boolean {
    val control = LocalReach.current ?: return true
    val state by control.state.collectAsState()
    return state.online
}
