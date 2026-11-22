package app.granger.feedback

import android.content.Intent
import app.granger.BuildConfig

/** The email for an [AskReport] (shared since Plan I7 Task 1), for [FeedbackMail.toMailApps]. */
fun AskReport.intent(): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
    putExtra(Intent.EXTRA_SUBJECT, subject())
    putExtra(Intent.EXTRA_TEXT, body())
}
