package app.granger.ebook

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

// Define's rules and the Wiktionary lookup are shared (core:screens Dictionary, Plan I5 Task 2). Handing a word to a
// dictionary app is Android's: an app that handles ACTION_PROCESS_TEXT, found through the PackageManager.

/** The phone's own dictionary on Android: an app that handles process-text, read-only. */
class ProcessTextDictionary(private val context: Context) : SystemDictionary {
    override fun available(): Boolean = Dictionary.hasProcessTextHandler(context)

    override fun define(text: String): Boolean = Dictionary.startProcessText(context, text)
}

private fun processTextIntent(): Intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

/** Whether some app handles process-text. Queries the package manager: call off the main thread. */
fun Dictionary.hasProcessTextHandler(context: Context): Boolean =
    context.packageManager.queryIntentActivities(processTextIntent(), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

/** Hands [text] to the process-text app, read-only. Returns false when none can take it. */
fun Dictionary.startProcessText(context: Context, text: String): Boolean = try {
    context.startActivity(
        processTextIntent()
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}
