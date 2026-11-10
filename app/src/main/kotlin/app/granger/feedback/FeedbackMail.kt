package app.granger.feedback

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import app.granger.AppContainer
import app.granger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Send feedback's email (Plan F1 part 2 Decision 2): written by the user and sent from their own mail app. */
object FeedbackMail {
    /** The FileProvider that serves `cache/reports/` only (manifest and `res/xml/report_paths.xml`). */
    fun authority(context: Context) = "${context.packageName}.reports"

    /**
     * An email to [BuildConfig.FEEDBACK_EMAIL] with the subject and the facts filled in, for [toMailApps] to aim at the
     * phone's mail apps. The attachment, when there is one, is written fresh to `cache/reports/` and lent read-only.
     */
    fun intent(context: Context, facts: FeedbackFacts, withCrashes: Boolean): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, FeedbackReport.subject(facts.build))
            putExtra(Intent.EXTRA_TEXT, FeedbackReport.body(facts, withCrashes))
        }
        FeedbackReport.attachment(facts, withCrashes) { it.file.readText() }?.let { text ->
            val file = File(context.cacheDir, "reports/granger-report.txt")
            file.parentFile?.mkdirs()
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, authority(context), file)
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri("granger-report.txt", uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return send
    }

    /**
     * The mail apps to offer, by package: the apps that answer `mailto:`, or only the phone's default among them.
     * Granger names them itself because a `mailto:` selector on the email failed to resolve on the Pixel 7 (release
     * 852, Android 16, with a work profile) even with the apps visible. The manifest's `<queries>` lets Granger see them.
     */
    fun mailApps(context: Context): List<String> {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(mailto, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        val preferred = pm.resolveActivity(mailto, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return pick(apps, preferred, ownPackage = context.packageName)
    }

    /**
     * [preferred] alone when it is one of [apps] (with no default it is the system's resolver, package `android`);
     * else every app, once each. The work profile's forwarder (also `android`) and Granger itself are never offered.
     */
    fun pick(apps: List<String>, preferred: String?, ownPackage: String): List<String> {
        val mail = apps.filter { it != "android" && it != ownPackage }.distinct()
        return if (preferred != null && preferred in mail) listOf(preferred) else mail
    }

    /** [email] sent straight to the one app, or a choice of only [apps]; null when there is no mail app. */
    fun toMailApps(email: Intent, apps: List<String>): Intent? = when (apps.size) {
        0 -> null
        1 -> Intent(email).setPackage(apps.single())
        else -> Intent.createChooser(Intent(email).setPackage(apps.first()), "Send feedback with")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, apps.drop(1).map { Intent(email).setPackage(it) }.toTypedArray())
    }

    /** [email] in the share sheet, for a phone with no mail app. */
    fun shareInstead(email: Intent): Intent = Intent.createChooser(email, "Send feedback with")

    /** The facts for this phone, read off the main thread. */
    suspend fun facts(container: AppContainer, crashLog: CrashLog): FeedbackFacts = withContext(Dispatchers.IO) {
        val volumes = runCatching { container.volumes.list() }.getOrDefault(emptyList())
        val chosen = container.downloadPrefs.volumePath?.let { path -> volumes.firstOrNull { it.root.path == path } }
            ?: volumes.firstOrNull()
        FeedbackFacts(
            build = BuildFacts.ofThisPhone(),
            tier = when {
                container.askOn -> "Library and Ask"
                !app.granger.Features.RESEARCH -> "Library (Ask off)"
                else -> "Library only"
            },
            freeBytes = chosen?.freeBytes,
            packs = container.library.packs.first().size,
            books = FeedbackReport.bookCount(container.bookshelf.books.first()),
            crashes = crashLog.list(),
        )
    }
}
