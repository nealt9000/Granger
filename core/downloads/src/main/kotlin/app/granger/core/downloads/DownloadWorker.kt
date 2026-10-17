package app.granger.core.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the worker can reach the one [DownloadRunner] of the process. */
interface DownloadsHost {
    val downloadRunner: DownloadRunner
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = DownloadNotifications(context)
    private val title: String get() = inputData.getString(KEY_TITLE).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(inputData.getString(KEY_ITEM_ID).orEmpty(), title, 0, 0)

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val hash = (inputData.getString(KEY_HASH) ?: inputData.getString(LEGACY_KEY_SHA256)) ?: return Result.failure()
        val runner = (applicationContext as DownloadsHost).downloadRunner
        try {
            setForeground(notifications.foregroundInfo(itemId, title, 0, 0))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background; download anyway.
        }
        var shownAt = 0L
        try {
            val outcome = runner.run(itemId, hash) { done, total ->
                val t = SystemClock.elapsedRealtime()
                if (t - shownAt >= NOTIFY_EVERY_MS) {
                    shownAt = t
                    notifications.update(itemId, title, done, total)
                }
            }
            if (outcome == RunOutcome.RETRY && isLastAttempt(runAttemptCount)) {
                // WorkManager retries forever; stop and show the last error with a Retry button.
                runner.giveUp(itemId, hash)
                return Result.failure()
            }
            return outcome.toWorkResult()
        } finally {
            // Also removes a progress notification posted when setForeground was refused.
            notifications.cancel(itemId)
        }
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val KEY_HASH = "hash"

        /** Work queued by the version before Plan 7 carries the checksum under this key. */
        private const val LEGACY_KEY_SHA256 = "sha256"
        const val KEY_TITLE = "title"
        private const val NOTIFY_EVERY_MS = 2_000L

        /** Attempts per download before giving up (WorkManager has no limit of its own). */
        const val MAX_ATTEMPTS = 10

        /** [runAttemptCount] counts from 0, so attempt [MAX_ATTEMPTS] is the last. */
        fun isLastAttempt(runAttemptCount: Int) = runAttemptCount >= MAX_ATTEMPTS - 1
    }
}

fun RunOutcome.toWorkResult(): ListenableWorker.Result = when (this) {
    RunOutcome.INSTALLED, RunOutcome.GONE -> ListenableWorker.Result.success()
    RunOutcome.RETRY -> ListenableWorker.Result.retry()
    RunOutcome.FAILED -> ListenableWorker.Result.failure()
}

class WorkManagerScheduler(context: Context) : DownloadScheduler {
    private val work = WorkManager.getInstance(context)

    override fun schedule(record: DownloadRecord, replace: Boolean) {
        val network = if (record.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_ITEM_ID to record.itemId,
                    DownloadWorker.KEY_HASH to record.hash,
                    DownloadWorker.KEY_TITLE to record.title,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        work.enqueueUniqueWork(name(record.itemId), policy, request)
    }

    override fun cancel(itemId: String) {
        work.cancelUniqueWork(name(itemId))
    }

    private fun name(itemId: String) = "download-$itemId"
}

internal class DownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    fun foregroundInfo(itemId: String, title: String, done: Long, total: Long) =
        ForegroundInfo(itemId.hashCode(), build(title, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Silently dropped when the user declined notifications. */
    fun update(itemId: String, title: String, done: Long, total: Long) {
        manager.notify(itemId.hashCode(), build(title, done, total))
    }

    fun cancel(itemId: String) {
        manager.cancel(itemId.hashCode())
    }

    private fun build(title: String, done: Long, total: Long): Notification {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { "Downloading" })
            .setContentText(if (total > 0) "$percent%" else "Starting…")
            .setProgress(100, percent, total <= 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "downloads"
    }
}
