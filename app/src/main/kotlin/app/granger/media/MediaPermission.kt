package app.granger.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether Granger may read the phone's shared audio list, as Settings shows it. */
enum class MediaPermissionStatus {
    GRANTED,

    /** Not granted, and asking is still possible (never asked, or the user can be asked again). */
    DENIED,

    /** Denied, and the system no longer shows the prompt: only the app's page in system settings can grant it. */
    PERMANENTLY_DENIED,
}

/** The audio permission for [sdk]: READ_MEDIA_AUDIO from API 33, READ_EXTERNAL_STORAGE (maxSdkVersion 32) below. */
fun audioPermission(sdk: Int = Build.VERSION.SDK_INT): String =
    if (sdk >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/** Whether Granger may read the phone's shared audio list. Never asks; only Settings does. */
fun hasAudioPermission(context: Context): Boolean =
    context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * Android says "should show rationale" false both before the first ask and after a denial the system won't prompt for
 * again, so [askedBefore] (Granger's own record) tells the two apart.
 */
fun mediaPermissionStatus(granted: Boolean, askedBefore: Boolean, showRationale: Boolean): MediaPermissionStatus = when {
    granted -> MediaPermissionStatus.GRANTED
    askedBefore && !showRationale -> MediaPermissionStatus.PERMANENTLY_DENIED
    else -> MediaPermissionStatus.DENIED
}

/**
 * Calls [onChange] when [granted] differs from what the last [check] saw (the user granted or revoked it in system
 * settings, or answered the prompt). The first check only records.
 */
class PermissionWatch(private val granted: () -> Boolean, private val onChange: () -> Unit) {
    private var last: Boolean? = null

    @Synchronized
    fun check() {
        val now = granted()
        val before = last
        last = now
        if (before != null && before != now) onChange()
    }
}

/** The status, and the two ways to change it: the system prompt and the app's page in system settings. */
class MediaPermissionState internal constructor(
    status: MediaPermissionStatus,
    private val launch: () -> Unit,
    private val settings: () -> Unit,
) {
    var status by mutableStateOf(status)
        internal set

    fun request() = launch()
    fun openAppSettings() = settings()
}

private const val PREFS = "media_permission"
private const val ASKED = "asked"

/**
 * The audio permission as the Settings screen asks for it, through the Activity Result API. Never asks by itself.
 * The status is checked again on every resume, since it can change in system settings; [onResult] runs after each
 * answer to the prompt and on every resume, so the caller can rescan when it changed ([PermissionWatch]).
 */
@Composable
fun rememberMediaPermission(onResult: (MediaPermissionStatus) -> Unit): MediaPermissionState {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val callback by rememberUpdatedState(onResult)
    fun current(): MediaPermissionStatus {
        val permission = audioPermission()
        val rationale = context.findActivity()?.shouldShowRequestPermissionRationale(permission) ?: false
        return mediaPermissionStatus(hasAudioPermission(context), prefs.getBoolean(ASKED, false), rationale)
    }
    val holder = remember { arrayOfNulls<MediaPermissionState>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val state = holder[0] ?: return@rememberLauncherForActivityResult
        state.status = current()
        callback(state.status)
    }
    val state = remember {
        MediaPermissionState(
            current(),
            launch = {
                prefs.edit().putBoolean(ASKED, true).apply()
                launcher.launch(audioPermission())
            },
            settings = { context.startActivity(appSettingsIntent(context)) },
        ).also { holder[0] = it }
    }
    LifecycleResumeEffect(Unit) {
        state.status = current()
        callback(state.status)
        onPauseOrDispose {}
    }
    return state
}

/** Granger's page in system settings, where a permanently denied permission can still be granted. */
fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
