package app.granger.voiceinput

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.granger.R
import app.granger.media.MediaPermissionStatus
import app.granger.media.appSettingsIntent
import app.granger.media.mediaPermissionStatus

/**
 * The mic (Plan A1), for the ask box and Search. Idle: a mic; listening: a filled mic that pulses gently, and a tap
 * stops the take. The microphone permission is asked for on the first tap, after one line saying why; once refused
 * for good, a tap says how to allow it in the phone's settings. Callers show it only when
 * [VoiceInputStatus.micShown].
 */
@Composable
fun MicButton(
    listening: Boolean,
    prefs: VoiceInputPrefs,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val start by rememberUpdatedState(onStart)
    var dialog by remember { mutableStateOf<MicDialog?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start()
    }

    fun ask() {
        prefs.setMicAsked()
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun tap() {
        if (listening) { onStop(); return }
        when (micPermission(context, prefs.micAsked)) {
            MediaPermissionStatus.GRANTED -> onStart()
            MediaPermissionStatus.DENIED -> dialog = MicDialog.WHY
            MediaPermissionStatus.PERMANENTLY_DENIED -> dialog = MicDialog.SETTINGS
        }
    }

    if (listening) {
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
            initialValue = 1f, targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "mic-pulse",
        )
        FilledIconButton(onClick = ::tap, modifier = modifier) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.voice_input_stop), Modifier.alpha(pulse))
        }
    } else {
        IconButton(onClick = ::tap, modifier = modifier, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.voice_input_speak))
        }
    }

    when (dialog) {
        MicDialog.WHY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.voice_input_permission_title)) },
            text = { Text(stringResource(R.string.voice_input_permission_why)) },
            confirmButton = { TextButton(onClick = { dialog = null; ask() }) { Text(stringResource(R.string.voice_input_permission_continue)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.voice_input_not_now)) } },
        )
        MicDialog.SETTINGS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.voice_input_permission_title)) },
            text = { Text(stringResource(R.string.voice_input_permission_settings)) },
            confirmButton = {
                TextButton(onClick = { dialog = null; context.startActivity(appSettingsIntent(context)) }) {
                    Text(stringResource(R.string.voice_input_open_settings))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.voice_input_not_now)) } },
        )
        null -> Unit
    }
}

private enum class MicDialog { WHY, SETTINGS }

/** Granted, askable, or refused for good (Android's rationale flag alone can't tell first-time from final). */
internal fun micPermission(context: Context, askedBefore: Boolean): MediaPermissionStatus {
    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val rationale = context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false
    return mediaPermissionStatus(granted, askedBefore, rationale)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
