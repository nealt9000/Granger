package app.granger.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.granger.core.media.PhoneScanner
import app.granger.core.media.ScanTriggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs phone scans: once on app start, 2 s after the system's media list stops changing, and at once when the audio
 * permission was granted or revoked since the last look ([recheckPermission], on every resume and after the prompt).
 * Without the permission the scanner marks phone items missing and asks nothing, so this never prompts or crashes.
 */
class PhoneMediaScans(private val context: Context, private val scope: CoroutineScope, private val scanner: PhoneScanner) {
    private suspend fun scanQuietly() {
        try {
            scanner.scan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("Granger", "Phone media scan failed", e)
        }
    }

    private val triggers = ScanTriggers(scope) { scanQuietly() }

    private val permission = PermissionWatch({ hasAudioPermission(context) }) {
        scope.launch { scanQuietly() }
        // Granted after start: the media list couldn't be watched without it.
        watchMediaList()
    }

    private var watching = false

    /** The audio permission may have changed in system settings or at the prompt: rescan if it did. */
    fun recheckPermission() = permission.check()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = triggers.mediaChanged()
    }

    fun start() {
        permission.check()
        triggers.appStarted()
        watchMediaList()
    }

    @Synchronized
    private fun watchMediaList() {
        if (watching) return
        try {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            watching = true
        } catch (e: SecurityException) {
            android.util.Log.w("Granger", "Can't watch the phone's media list", e)
        }
    }
}
