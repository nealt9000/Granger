package app.granger.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.granger.core.media.PhoneScanner
import app.granger.core.media.ScanTriggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Whether Granger may read the phone's shared audio list. Never asks; Settings does (Plan M1 Task 12). */
fun hasAudioPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Runs phone scans: once on app start, and 2 s after the system's media list stops changing. Without the audio
 * permission the scanner marks phone items missing and asks nothing, so this never prompts or crashes.
 */
class PhoneMediaScans(private val context: Context, scope: CoroutineScope, private val scanner: PhoneScanner) {
    private val triggers = ScanTriggers(scope) {
        try {
            scanner.scan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("Granger", "Phone media scan failed", e)
        }
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = triggers.mediaChanged()
    }

    fun start() {
        triggers.appStarted()
        try {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: SecurityException) {
            android.util.Log.w("Granger", "Can't watch the phone's media list", e)
        }
    }
}
