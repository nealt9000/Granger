package app.granger.feedback

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.granger.AppContainer
import app.granger.BuildConfig
import app.granger.core.ai.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Send feedback's email (Plan F1 part 2 Decision 2): written by the user and sent from their own mail app. */
object FeedbackMail {
    /** The FileProvider that serves `cache/reports/` only (manifest and `res/xml/report_paths.xml`). */
    fun authority(context: Context) = "${context.packageName}.reports"

    /**
     * An email to [BuildConfig.FEEDBACK_EMAIL] with the subject and the facts filled in. The selector keeps the choice
     * to mail apps; the attachment, when there is one, is written fresh to `cache/reports/` and lent read-only.
     */
    fun intent(context: Context, facts: FeedbackFacts, withCrashes: Boolean): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, FeedbackReport.subject(facts.build))
            putExtra(Intent.EXTRA_TEXT, FeedbackReport.body(facts, withCrashes))
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
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

    /** [email] without its mail-only selector, in the share sheet: for a phone where no mail app answers mailto. */
    fun shareInstead(email: Intent): Intent =
        Intent.createChooser(Intent(email).apply { selector = null }, "Send feedback with")

    /** The facts for this phone, read off the main thread. */
    suspend fun facts(container: AppContainer, crashLog: CrashLog): FeedbackFacts = withContext(Dispatchers.IO) {
        val volumes = runCatching { container.volumes.list() }.getOrDefault(emptyList())
        val chosen = container.downloadPrefs.volumePath?.let { path -> volumes.firstOrNull { it.root.path == path } }
            ?: volumes.firstOrNull()
        FeedbackFacts(
            build = BuildFacts.ofThisPhone(),
            tier = if (container.deviceTier >= DeviceTier.ASK) "Library and Ask" else "Library only",
            freeBytes = chosen?.freeBytes,
            packs = container.library.packs.first().size,
            books = container.bookshelf.books.first().size,
            crashes = crashLog.list(),
        )
    }
}
